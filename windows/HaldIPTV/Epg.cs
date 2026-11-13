using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;

namespace HaldIPTV;

/// Channel-name keys shared with the macOS / Android apps ("Первый канал HD" == "Первый канал").
public static class NameKeys
{
    static readonly HashSet<string> Quality = new() { "uhd", "fhd", "hd", "4k", "sd", "hevc", "orig", "original", "50", "60", "hq" };
    static readonly HashSet<string> Generic = new() { "телеканал", "канал", "tv", "тв", "channel" };
    static readonly Regex Country = new("^[a-z]{2}$", RegexOptions.Compiled);
    static readonly Regex Brackets = new(@"\[[^\]]*\]|\([^)]*\)", RegexOptions.Compiled);
    static readonly Regex Timeshift = new(@"\s\+\s*\d+.*$", RegexOptions.Compiled);
    static readonly Regex Split = new(@"[^\p{L}\p{N}_+!]+", RegexOptions.Compiled);
    static readonly Dictionary<char, string> Tr = new()
    {
        ['а'] = "a", ['б'] = "b", ['в'] = "v", ['г'] = "g", ['д'] = "d", ['е'] = "e", ['ё'] = "e", ['ж'] = "zh", ['з'] = "z",
        ['и'] = "i", ['й'] = "y", ['к'] = "k", ['л'] = "l", ['м'] = "m", ['н'] = "n", ['о'] = "o", ['п'] = "p", ['р'] = "r",
        ['с'] = "s", ['т'] = "t", ['у'] = "u", ['ф'] = "f", ['х'] = "kh", ['ц'] = "ts", ['ч'] = "ch", ['ш'] = "sh", ['щ'] = "shch",
        ['ъ'] = "", ['ы'] = "y", ['ь'] = "", ['э'] = "e", ['ю'] = "yu", ['я'] = "ya", ['і'] = "i", ['ї'] = "yi", ['є'] = "ye", ['ґ'] = "g",
    };

    static List<string> Words(string s)
    {
        var t = s.ToLowerInvariant().Replace('ё', 'е');
        t = Timeshift.Replace(Brackets.Replace(t, " "), " ");
        var w = Split.Split(t).Where(x => x.Length > 0).Select(x => x == "+" ? "plus" : x).ToList();
        while (w.Count > 1 && (Quality.Contains(w[^1]) || Country.IsMatch(w[^1]))) w.RemoveAt(w.Count - 1);
        if (w.Count > 1 && w[0] == "vf") w.RemoveAt(0);
        return w;
    }

    static string Join(IEnumerable<string> w)
    {
        var sb = new StringBuilder();
        foreach (var ch in string.Concat(w)) if (char.IsLetterOrDigit(ch) || ch == '_') sb.Append(ch);
        return sb.ToString();
    }

    static string Translit(string s)
    {
        var sb = new StringBuilder();
        foreach (var ch in s) sb.Append(Tr.TryGetValue(ch, out var r) ? r : ch.ToString());
        return sb.ToString();
    }

    public static List<string> Keys(string name)
    {
        var w = Words(name);
        var outp = new List<string> { Join(w) };
        var g = w.Where(x => !Generic.Contains(x)).ToList();
        if (g.Count > 0 && g.Count != w.Count) outp.Add(Join(g));
        return outp.Where(k => k.Length > 0).SelectMany(k => new[] { k, Translit(k) }).Distinct().ToList();
    }
}

/// TV guide (XMLTV). Shows the saved compact guide instantly; downloads a fresh one at most every 8 hours.
public class EpgService
{
    /// iLook TV guide first, then a spare source
    public static readonly string[] DefaultSources = { "http://epg.it999.ru/epg2.xml.gz", "https://iptvx.one/epg/epg.xml.gz" };

    Dictionary<string, List<Programme>> _byChannel = new();
    public Dictionary<string, string> Icons { get; private set; } = new();
    public string? LoadedFrom { get; private set; }

    class Compact
    {
        public DateTime SavedAt { get; set; }
        public Dictionary<string, List<Programme>> ByChannel { get; set; } = new();
        public Dictionary<string, string> Icons { get; set; } = new();
    }

    static string CompactPath(Guid playlist) => Path.Combine(Storage.CacheDir, $"epg-compact-{playlist}.json");

    /// Returns the age of the saved guide, or null when there is none.
    public async Task<TimeSpan?> Restore(Guid playlist)
    {
        var path = CompactPath(playlist);
        if (!File.Exists(path)) return null;
        try
        {
            var c = await Task.Run(() => JsonSerializer.Deserialize<Compact>(File.ReadAllBytes(path)));
            if (c == null) return null;
            _byChannel = c.ByChannel;
            Icons = c.Icons;
            return DateTime.UtcNow - c.SavedAt;
        }
        catch { return null; }
    }

    public async Task Refresh(IEnumerable<string> sources, string? ua, List<Channel> channels, Guid playlist)
    {
        Exception? last = null;
        foreach (var src in sources)
        {
            try
            {
                var raw = await Download(src, ua);
                var compact = await Task.Run(() => Parse(raw, channels));
                if (compact.ByChannel.Count == 0) throw new Exception("empty guide");
                _byChannel = compact.ByChannel;
                Icons = compact.Icons;
                LoadedFrom = src;
                _ = Task.Run(() => File.WriteAllBytes(CompactPath(playlist), JsonSerializer.SerializeToUtf8Bytes(compact)));
                return;
            }
            catch (Exception e) { last = e; }
        }
        throw last ?? new Exception("no guide");
    }

    static async Task<string> Download(string url, string? ua)
    {
        var path = Path.Combine(Storage.CacheDir, $"epg-raw-{(url.GetHashCode() & 0x7fffffff)}.bin");
        if (!File.Exists(path) || DateTime.Now - File.GetLastWriteTime(path) > TimeSpan.FromHours(8) || new FileInfo(path).Length < 1000)
        {
            var data = await Net.GetBytes(url, string.IsNullOrEmpty(ua) ? "Mozilla/5.0" : ua);
            await File.WriteAllBytesAsync(path, data);
        }
        return path;
    }

    static Compact Parse(string path, List<Channel> channels)
    {
        var keyToIds = new Dictionary<string, List<string>>();
        foreach (var c in channels)
            foreach (var k in NameKeys.Keys(c.TvgName ?? c.Name))
            {
                if (!keyToIds.TryGetValue(k, out var l)) keyToIds[k] = l = new();
                l.Add(c.Id);
            }
        var byTvgId = channels.Where(c => !string.IsNullOrEmpty(c.TvgId)).GroupBy(c => c.TvgId!).ToDictionary(g => g.Key, g => g.Select(c => c.Id).ToList());

        var xmlToOurs = new Dictionary<string, List<string>>();
        var icons = new Dictionary<string, string>();
        var progs = new Dictionary<string, List<Programme>>();
        var from = DateTime.UtcNow.AddHours(-12);
        var to = DateTime.UtcNow.AddHours(36);

        using var fs = File.OpenRead(path);
        Stream s = new BufferedStream(fs, 65536);
        var b1 = s.ReadByte(); var b2 = s.ReadByte();
        fs.Position = 0; s = new BufferedStream(fs, 65536);
        if (b1 == 0x1f && b2 == 0x8b) s = new GZipStream(s, CompressionMode.Decompress);

        using var r = XmlReader.Create(s, new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreWhitespace = true });
        string? chId = null; string? chIcon = null; var chNames = new List<string>();
        // Skip()/ReadElementContentAsString() already move to the next node: don't Read() past it
        bool moved = false;
        while (moved || r.Read())
        {
            moved = false;
            if (r.NodeType == XmlNodeType.Element)
            {
                switch (r.Name)
                {
                    case "channel":
                        chId = r.GetAttribute("id"); chIcon = null; chNames.Clear();
                        break;
                    case "display-name" when chId != null:
                        chNames.Add(r.ReadElementContentAsString().Trim());
                        moved = true;
                        break;
                    case "icon" when chId != null && chIcon == null:
                        chIcon = r.GetAttribute("src");
                        break;
                    case "programme":
                        var ch = r.GetAttribute("channel");
                        if (ch == null || !xmlToOurs.TryGetValue(ch, out var ours)) { r.Skip(); moved = true; break; }
                        var st = ParseDate(r.GetAttribute("start")); var en = ParseDate(r.GetAttribute("stop"));
                        if (st == null || en == null || en < from || st > to) { r.Skip(); moved = true; break; }
                        string title = "";
                        using (var sub = r.ReadSubtree())
                            while (sub.Read())
                                if (sub.NodeType == XmlNodeType.Element && sub.Name == "title") { title = sub.ReadElementContentAsString(); break; }
                        var p = new Programme(st.Value, en.Value, title, null);
                        foreach (var id in ours) { if (!progs.TryGetValue(id, out var l)) progs[id] = l = new(); l.Add(p); }
                        break;
                }
            }
            else if (r.NodeType == XmlNodeType.EndElement && r.Name == "channel" && chId != null)
            {
                var ours = new HashSet<string>();
                if (byTvgId.TryGetValue(chId, out var t)) ours.UnionWith(t);
                foreach (var n in chNames) foreach (var k in NameKeys.Keys(n)) if (keyToIds.TryGetValue(k, out var l)) ours.UnionWith(l);
                if (ours.Count > 0)
                {
                    xmlToOurs[chId] = ours.ToList();
                    if (!string.IsNullOrEmpty(chIcon)) foreach (var id in ours) icons.TryAdd(id, chIcon!);
                }
                chId = null;
            }
        }
        foreach (var l in progs.Values) l.Sort((a, b) => a.Start.CompareTo(b.Start));
        return new Compact { SavedAt = DateTime.UtcNow, ByChannel = progs, Icons = icons };
    }

    static DateTime? ParseDate(string? s)
    {
        if (string.IsNullOrWhiteSpace(s) || s.Length < 14) return null;
        if (!DateTime.TryParseExact(s[..14], "yyyyMMddHHmmss", CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var t))
            return null;
        var tz = s[14..].Trim();
        if (tz.Length >= 5 && (tz[0] == '+' || tz[0] == '-') && int.TryParse(tz[1..3], out var h) && int.TryParse(tz[3..5], out var m))
            t = tz[0] == '+' ? t.AddHours(-h).AddMinutes(-m) : t.AddHours(h).AddMinutes(m);
        return t;
    }

    public List<Programme> Schedule(Channel c) => _byChannel.TryGetValue(c.Id, out var l) ? l : new();

    public (Programme? now, Programme? next) NowNext(Channel c)
    {
        var l = Schedule(c);
        var t = DateTime.UtcNow;
        var i = l.FindIndex(x => x.Start <= t && x.Stop > t);
        if (i < 0) return (null, l.FirstOrDefault(x => x.Start > t));
        return (l[i], i + 1 < l.Count ? l[i + 1] : null);
    }
}
