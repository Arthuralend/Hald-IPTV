using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;

namespace HaldIPTV;

// ───────────────────────── Models ─────────────────────────

public class Channel
{
    public string Name { get; set; } = "";
    public string Url { get; set; } = "";
    public string? Logo { get; set; }
    public string Group { get; set; } = "";
    public string? TvgId { get; set; }
    public string? TvgName { get; set; }
    public string? UserAgent { get; set; }
    public string? Catchup { get; set; }
    public string? CatchupSource { get; set; }
    public int? CatchupDays { get; set; }

    public string Id => Url + "|" + Name;

    public bool HasArchive
    {
        get
        {
            var c = Catchup?.ToLowerInvariant();
            return !string.IsNullOrEmpty(c) && c != "0" && c != "false";
        }
    }
}

public class Playlist
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "";
    public string Source { get; set; } = "";
    public string? EpgUrl { get; set; }
    public string? UserAgent { get; set; }
    public DateTime? LastUpdated { get; set; }
    public int AutoRefreshHours { get; set; } = 24;
}

public record Programme(DateTime Start, DateTime Stop, string Title, string? Desc)
{
    public double Progress(DateTime now)
    {
        var total = (Stop - Start).TotalSeconds;
        return total <= 0 ? 0 : Math.Clamp((now - Start).TotalSeconds / total, 0, 1);
    }
}

public class Settings
{
    public int NetworkCaching { get; set; } = 1500;
    public string DefaultUserAgent { get; set; } = "";
    public bool UseLogoDb { get; set; } = true;
    public bool PreferLogoDb { get; set; } = false;
    public string RecordingsFolder { get; set; } = "";
    public int Volume { get; set; } = 80;
    public bool GridMode { get; set; } = false;
    public Guid? SelectedPlaylist { get; set; }
}

// ───────────────────────── Localization ─────────────────────────

public static class Loc
{
    public static readonly bool IsRussian =
        new[] { "ru", "be", "uk", "kk" }.Contains(CultureInfo.CurrentUICulture.TwoLetterISOLanguageName);

    static readonly Dictionary<string, string> En = new()
    {
        ["Все каналы"] = "All Channels", ["Избранное"] = "Favorites", ["Недавние"] = "Recent",
        ["Без группы"] = "Ungrouped", ["Плейлист"] = "Playlist", ["Группы"] = "Groups",
        ["Добавить"] = "Add", ["Изменить"] = "Edit", ["Поиск канала"] = "Search channels",
        ["Обновить плейлист"] = "Refresh playlist", ["Добавьте плейлист"] = "Add a playlist",
        ["Нет каналов"] = "No channels", ["Убрать из избранного"] = "Remove from Favorites",
        ["В избранное"] = "Add to Favorites", ["Копировать ссылку"] = "Copy stream URL",
        ["Очистить недавние"] = "Clear Recent", ["Убрать из недавних"] = "Remove from Recent",
        ["Выберите канал"] = "Select a channel", ["Далее:"] = "Next:",
        ["Полный экран канала"] = "Channel full screen", ["Запись"] = "Record",
        ["Остановить запись"] = "Stop recording", ["Открыть папку записей"] = "Open recordings folder",
        ["Новый плейлист"] = "New playlist", ["Название"] = "Name", ["Ссылка M3U / M3U8 или файл"] = "M3U / M3U8 URL or file",
        ["Файл…"] = "File…", ["EPG (XMLTV), необязательно"] = "EPG (XMLTV), optional",
        ["User-Agent, необязательно"] = "User-Agent, optional", ["Автообновление, часов"] = "Auto-refresh, hours",
        ["Удалить"] = "Delete", ["Отмена"] = "Cancel", ["Сохранить"] = "Save", ["Настройки"] = "Settings",
        ["Буфер сети, мс"] = "Network buffer, ms", ["User-Agent по умолчанию"] = "Default User-Agent",
        ["Очистить кэш логотипов"] = "Clear logo cache",
        ["Логотипы из базы iptv-org для каналов без значка"] = "Fetch missing logos from iptv-org database",
        ["Всегда предпочитать логотипы из базы"] = "Always prefer database logos",
        ["Папка записей"] = "Recordings folder", ["Выбрать…"] = "Choose…",
        ["Подключение…"] = "Connecting…", ["Пауза"] = "Paused",
        ["Ошибка воспроизведения потока"] = "Stream playback error",
        ["Не удалось загрузить плейлист: "] = "Failed to load playlist: ",
        ["В плейлисте не найдено каналов"] = "No channels found in playlist",
        ["каналов"] = "channels", ["Запись сохранена"] = "Recording saved",
        ["Архив недоступен для этого канала"] = "Archive is not available for this channel",
        ["Назад 5 мин"] = "Back 5 min", ["Назад 30 с"] = "Back 30 s", ["Вперёд 30 с"] = "Forward 30 s",
        ["Вперёд 5 мин"] = "Forward 5 min", ["С начала передачи"] = "Start over",
        ["Вернуться в прямой эфир"] = "Back to live", ["ЭФИР"] = "LIVE",
        ["Предыдущий канал"] = "Previous channel", ["Следующий канал"] = "Next channel",
        ["Пауза / Продолжить"] = "Pause / Resume", ["Без звука"] = "Mute", ["Аудиодорожка"] = "Audio track",
        ["Поверх всех окон"] = "Keep on top", ["Список"] = "List", ["Сетка"] = "Grid", ["Телевизор"] = "TV",
        ["Встроенный плейлист не настроен в этой сборке"] = "The built-in playlist is not configured in this build",
    };

    public static string T(string s) => IsRussian ? s : (En.TryGetValue(s, out var e) ? e : s);
}

// ───────────────────────── Storage ─────────────────────────

public static class Storage
{
    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "HaldIPTV");
    public static readonly string CacheDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HaldIPTV");

    static readonly JsonSerializerOptions Opts = new() { WriteIndented = false };

    static Storage()
    {
        Directory.CreateDirectory(Dir);
        Directory.CreateDirectory(CacheDir);
    }

    public static T? Load<T>(string name)
    {
        try
        {
            var p = Path.Combine(Dir, name);
            return File.Exists(p) ? JsonSerializer.Deserialize<T>(File.ReadAllText(p), Opts) : default;
        }
        catch { return default; }
    }

    public static void Save<T>(string name, T value)
    {
        try { File.WriteAllText(Path.Combine(Dir, name), JsonSerializer.Serialize(value, Opts)); } catch { }
    }

    public static void Delete(string name)
    {
        try { File.Delete(Path.Combine(Dir, name)); } catch { }
    }
}

public static class Net
{
    public static readonly HttpClient Client = Create();

    static HttpClient Create()
    {
        var h = new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All, AllowAutoRedirect = true };
        var c = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(120) };
        return c;
    }

    public static async Task<byte[]> GetBytes(string url, string? userAgent = null)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", string.IsNullOrEmpty(userAgent) ? "VLC/3.0.21 LibVLC/3.0.21" : userAgent);
        using var resp = await Client.SendAsync(req);
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadAsByteArrayAsync();
    }
}

// ───────────────────────── M3U parser ─────────────────────────

public static class M3UParser
{
    public const string NoGroup = "Без группы";
    static readonly Regex Attr = new(@"([\w\-]+)=""([^""]*)""", RegexOptions.Compiled);

    public static (List<Channel> channels, string? epg) Parse(string text)
    {
        var list = new List<Channel>();
        string? epg = null;
        var header = new Dictionary<string, string>();
        Dictionary<string, string> attrs = new();
        string? name = null, grp = null, ua = null;

        foreach (var raw in text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith("#EXTM3U"))
            {
                header = Attributes(line);
                epg = header.GetValueOrDefault("url-tvg") ?? header.GetValueOrDefault("x-tvg-url") ?? header.GetValueOrDefault("tvg-url");
                if (epg != null && epg.Contains(',')) epg = epg.Split(',')[0];
            }
            else if (line.StartsWith("#EXTINF"))
            {
                attrs = Attributes(line);
                name = TitleAfterComma(line);
                grp = null;
            }
            else if (line.StartsWith("#EXTGRP:")) grp = line[8..].Trim();
            else if (line.StartsWith("#EXTVLCOPT:"))
            {
                var opt = line[11..];
                if (opt.StartsWith("http-user-agent=", StringComparison.OrdinalIgnoreCase)) ua = opt["http-user-agent=".Length..];
            }
            else if (line.StartsWith('#')) continue;
            else
            {
                var group = attrs.GetValueOrDefault("group-title") ?? grp ?? NoGroup;
                if (string.IsNullOrWhiteSpace(group)) group = NoGroup;
                var rec = attrs.GetValueOrDefault("timeshift") ?? attrs.GetValueOrDefault("tvg-rec");
                var catchup = attrs.GetValueOrDefault("catchup") ?? attrs.GetValueOrDefault("catchup-type") ?? header.GetValueOrDefault("catchup");
                if (catchup == null && !string.IsNullOrEmpty(rec) && rec != "0") catchup = "shift";
                int.TryParse(attrs.GetValueOrDefault("catchup-days") ?? rec ?? header.GetValueOrDefault("catchup-days"), out var days);
                var logo = attrs.GetValueOrDefault("tvg-logo");
                list.Add(new Channel
                {
                    Name = !string.IsNullOrEmpty(name) ? name! : attrs.GetValueOrDefault("tvg-name") ?? line,
                    Url = line,
                    Logo = string.IsNullOrEmpty(logo) ? null : logo,
                    Group = group,
                    TvgId = attrs.GetValueOrDefault("tvg-id"),
                    TvgName = attrs.GetValueOrDefault("tvg-name"),
                    UserAgent = ua ?? attrs.GetValueOrDefault("user-agent"),
                    Catchup = catchup,
                    CatchupSource = attrs.GetValueOrDefault("catchup-source") ?? header.GetValueOrDefault("catchup-source"),
                    CatchupDays = days > 0 ? days : null,
                });
                attrs = new(); name = null; grp = null; ua = null;
            }
        }
        return (list, epg);
    }

    static Dictionary<string, string> Attributes(string line)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match m in Attr.Matches(line)) d[m.Groups[1].Value.ToLowerInvariant()] = m.Groups[2].Value;
        return d;
    }

    static string TitleAfterComma(string line)
    {
        bool q = false; int last = -1;
        for (int i = 0; i < line.Length; i++)
        {
            if (line[i] == '"') q = !q;
            else if (line[i] == ',' && !q) last = i;
        }
        return last < 0 ? "" : line[(last + 1)..].Trim();
    }
}

// ───────────────────────── Catch-up (archive) URLs ─────────────────────────

public static class Catchup
{
    static string Q(string url) => url.Contains('?') ? "&" : "?";

    public static string? Url(Channel c, DateTime startUtc, DateTime nowUtc)
    {
        if (!c.HasArchive) return null;
        long s = new DateTimeOffset(startUtc).ToUnixTimeSeconds();
        long n = new DateTimeOffset(nowUtc).ToUnixTimeSeconds();
        long dur = Math.Max(60, n - s);
        var type = c.Catchup!.ToLowerInvariant();
        switch (type)
        {
            case "flussonic": case "flussonic-hls": case "flussonic-ts": case "fs":
                return Flussonic(c.Url, s, dur);
            case "shift": case "timeshift": case "archive":
                if (!string.IsNullOrEmpty(c.CatchupSource)) return Fill(c.CatchupSource!, startUtc, nowUtc);
                return c.Url + Q(c.Url) + $"utc={s}&lutc={n}";
            case "append":
                return c.Url + (c.CatchupSource != null ? Fill(c.CatchupSource, startUtc, nowUtc) : Q(c.Url) + $"utc={s}&lutc={n}");
            default:
                if (!string.IsNullOrEmpty(c.CatchupSource))
                {
                    var f = Fill(c.CatchupSource!, startUtc, nowUtc);
                    return f.StartsWith("http") ? f : c.Url + f;
                }
                if (c.Url.Contains("/index.m3u8") || c.Url.Contains("/mpegts") || c.Url.Contains("/video.m3u8"))
                    return Flussonic(c.Url, s, dur);
                return c.Url + Q(c.Url) + $"utc={s}&lutc={n}";
        }
    }

    static string Flussonic(string url, long start, long dur)
    {
        foreach (var name in new[] { "index", "video", "mono", "playlist" })
            if (url.Contains($"/{name}.m3u8")) return url.Replace($"/{name}.m3u8", $"/{name}-{start}-{dur}.m3u8");
        if (url.Contains("/mpegts")) return url.Replace("/mpegts", $"/timeshift_abs-{start}.ts");
        var parts = url.Split('?', 2);
        var q = parts.Length > 1 ? "?" + parts[1] : "";
        return parts[0].EndsWith(".m3u8")
            ? parts[0].Replace(".m3u8", $"-{start}-{dur}.m3u8") + q
            : parts[0] + $"/timeshift_abs-{start}.ts" + q;
    }

    static string Fill(string t, DateTime start, DateTime now)
    {
        long s = new DateTimeOffset(start).ToUnixTimeSeconds();
        long n = new DateTimeOffset(now).ToUnixTimeSeconds();
        var map = new Dictionary<string, string>
        {
            ["utc"] = $"{s}", ["start"] = $"{s}", ["lutc"] = $"{n}", ["now"] = $"{n}", ["timestamp"] = $"{n}",
            ["utcend"] = $"{n}", ["end"] = $"{n}", ["duration"] = $"{n - s}", ["offset"] = $"{n - s}",
            ["Y"] = start.ToString("yyyy"), ["m"] = start.ToString("MM"), ["d"] = start.ToString("dd"),
            ["H"] = start.ToString("HH"), ["M"] = start.ToString("mm"), ["S"] = start.ToString("ss"),
        };
        foreach (var (k, v) in map) t = t.Replace("${" + k + "}", v).Replace("{" + k + "}", v);
        return t;
    }
}

// ───────────────────────── EPG (XMLTV) ─────────────────────────

public class EpgService
{
    Dictionary<string, List<Programme>> _progs = new();
    Dictionary<string, string> _names = new();
    public string? LoadedFrom { get; private set; }

    public async Task Load(string url, string? ua)
    {
        var data = await Net.GetBytes(url, ua);
        Stream s = new MemoryStream(data);
        if (data.Length > 2 && data[0] == 0x1f && data[1] == 0x8b) s = new GZipStream(s, CompressionMode.Decompress);
        var progs = new Dictionary<string, List<Programme>>();
        var names = new Dictionary<string, string>();
        var from = DateTime.UtcNow.AddHours(-6);
        var to = DateTime.UtcNow.AddHours(36);
        await Task.Run(() =>
        {
            using var r = XmlReader.Create(s, new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreWhitespace = true });
            string? chId = null;
            while (r.Read())
            {
                if (r.NodeType != XmlNodeType.Element) continue;
                if (r.Name == "channel") chId = r.GetAttribute("id");
                else if (r.Name == "display-name" && chId != null)
                {
                    var n = r.ReadElementContentAsString().Trim().ToLowerInvariant();
                    if (n.Length > 0) names[n] = chId;
                }
                else if (r.Name == "programme")
                {
                    var ch = r.GetAttribute("channel");
                    var st = ParseDate(r.GetAttribute("start"));
                    var en = ParseDate(r.GetAttribute("stop"));
                    string title = "", desc = "";
                    using (var sub = r.ReadSubtree())
                    {
                        while (sub.Read())
                        {
                            if (sub.NodeType != XmlNodeType.Element) continue;
                            if (sub.Name == "title" && title == "") title = sub.ReadElementContentAsString();
                            else if (sub.Name == "desc" && desc == "") desc = sub.ReadElementContentAsString();
                        }
                    }
                    if (ch != null && st != null && en != null && en > from && st < to)
                    {
                        if (!progs.TryGetValue(ch, out var l)) progs[ch] = l = new();
                        l.Add(new Programme(st.Value, en.Value, title, desc == "" ? null : desc));
                    }
                }
            }
            foreach (var l in progs.Values) l.Sort((a, b) => a.Start.CompareTo(b.Start));
        });
        _progs = progs; _names = names; LoadedFrom = url;
    }

    static DateTime? ParseDate(string? s)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        s = s.Trim();
        if (DateTimeOffset.TryParseExact(s, "yyyyMMddHHmmss zzz", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d))
            return d.UtcDateTime;
        if (s.Length >= 20)
        {
            var tz = s[15..].Trim();
            if (tz.Length == 5) tz = tz[..3] + ":" + tz[3..];
            if (DateTimeOffset.TryParseExact(s[..14] + " " + tz, "yyyyMMddHHmmss zzz", CultureInfo.InvariantCulture, DateTimeStyles.None, out d))
                return d.UtcDateTime;
        }
        if (s.Length >= 14 && DateTime.TryParseExact(s[..14], "yyyyMMddHHmmss", CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var u))
            return u;
        return null;
    }

    List<Programme> Schedule(Channel c)
    {
        if (!string.IsNullOrEmpty(c.TvgId) && _progs.TryGetValue(c.TvgId!, out var p)) return p;
        foreach (var n in new[] { c.TvgName, c.Name })
            if (n != null && _names.TryGetValue(n.ToLowerInvariant(), out var id) && _progs.TryGetValue(id, out p)) return p;
        return new();
    }

    public (Programme? now, Programme? next) NowNext(Channel c)
    {
        var l = Schedule(c);
        var t = DateTime.UtcNow;
        var i = l.FindIndex(x => x.Start <= t && x.Stop > t);
        if (i < 0) return (null, l.FirstOrDefault(x => x.Start > t));
        return (l[i], i + 1 < l.Count ? l[i + 1] : null);
    }
}

// ───────────────────────── Logo database (iptv-org) ─────────────────────────

public static class LogoDatabase
{
    static readonly string[] Mirrors = { "https://iptv-org.github.io/api/", "https://raw.githubusercontent.com/iptv-org/api/gh-pages/" };
    static readonly Dictionary<string, List<string>> NameIndex = new();
    static readonly Dictionary<string, string> IdIndex = new();
    static readonly Dictionary<string, string> Logos = new();
    static Task? _load;

    public static Task EnsureLoaded() => _load ??= Task.Run(Load);

    static async Task<byte[]?> Fetch(string file)
    {
        var local = Path.Combine(Storage.CacheDir, file);
        if (File.Exists(local) && DateTime.Now - File.GetLastWriteTime(local) < TimeSpan.FromDays(7))
            return await File.ReadAllBytesAsync(local);
        foreach (var m in Mirrors)
        {
            try
            {
                var d = await Net.GetBytes(m + file, "Mozilla/5.0");
                if (d.Length > 1000) { await File.WriteAllBytesAsync(local, d); return d; }
            }
            catch { }
        }
        return File.Exists(local) ? await File.ReadAllBytesAsync(local) : null;
    }

    static async Task Load()
    {
        var cd = await Fetch("channels.json");
        var ld = await Fetch("logos.json");
        if (cd == null || ld == null) return;
        using (var doc = JsonDocument.Parse(ld))
            foreach (var l in doc.RootElement.EnumerateArray())
            {
                if (l.TryGetProperty("in_use", out var iu) && iu.ValueKind == JsonValueKind.False) continue;
                var ch = l.GetProperty("channel").GetString()!;
                var feedNull = !l.TryGetProperty("feed", out var f) || f.ValueKind == JsonValueKind.Null;
                if (feedNull || !Logos.ContainsKey(ch)) Logos[ch] = l.GetProperty("url").GetString()!;
            }
        using (var doc = JsonDocument.Parse(cd))
            foreach (var c in doc.RootElement.EnumerateArray())
            {
                var id = c.GetProperty("id").GetString()!;
                if (!Logos.ContainsKey(id)) continue;
                IdIndex[id.ToLowerInvariant()] = id;
                var names = new List<string> { c.GetProperty("name").GetString()! };
                if (c.TryGetProperty("alt_names", out var alt) && alt.ValueKind == JsonValueKind.Array)
                    names.AddRange(alt.EnumerateArray().Select(a => a.GetString() ?? ""));
                foreach (var n in names)
                {
                    var k = Normalize(n);
                    if (k.Length == 0) continue;
                    Add(k, id);
                    var tk = Translit(k);
                    if (tk != k) Add(tk, id);
                }
            }
    }

    static void Add(string k, string id)
    {
        if (!NameIndex.TryGetValue(k, out var l)) NameIndex[k] = l = new();
        l.Add(id);
    }

    public static async Task<string?> Find(Channel c)
    {
        await EnsureLoaded();
        if (!string.IsNullOrEmpty(c.TvgId))
        {
            var b = c.TvgId!.Split('@')[0].ToLowerInvariant();
            if (IdIndex.TryGetValue(b, out var id) && Logos.TryGetValue(id, out var u)) return u;
        }
        foreach (var n in new[] { c.TvgName, c.Name })
        {
            if (string.IsNullOrEmpty(n)) continue;
            var k = Normalize(n!);
            if (k.Length == 0) continue;
            if (!NameIndex.TryGetValue(k, out var ids)) NameIndex.TryGetValue(Translit(k), out ids);
            if (ids == null || ids.Count == 0) continue;
            var cyr = n!.Any(ch => ch >= 'Ѐ' && ch <= 'ӿ');
            var pref = cyr ? new[] { ".ru", ".am", ".by", ".kz", ".ua" } : new[] { ".am", ".ru", ".us", ".uk" };
            string? best = null;
            foreach (var p in pref)
            {
                best = ids.FirstOrDefault(i => i.EndsWith(p, StringComparison.OrdinalIgnoreCase) && !i.Contains("International"));
                if (best != null) break;
            }
            best ??= ids[0];
            if (Logos.TryGetValue(best, out var url)) return url;
        }
        return null;
    }

    static readonly Regex Bracket = new(@"\[[^\]]*\]|\([^)]*\)", RegexOptions.Compiled);
    static readonly Regex Junk = new(@"\b(hd|fhd|uhd|4k|sd|hevc|orig|original|int|international|\+\d+|-\d+)\b", RegexOptions.Compiled);

    public static string Normalize(string s)
    {
        var t = Junk.Replace(Bracket.Replace(s.ToLowerInvariant(), " "), " ");
        var sb = new StringBuilder();
        foreach (var ch in t) if (char.IsLetterOrDigit(ch)) sb.Append(ch);
        return sb.ToString();
    }

    static readonly Dictionary<char, string> Tr = new()
    {
        ['а'] = "a", ['б'] = "b", ['в'] = "v", ['г'] = "g", ['д'] = "d", ['е'] = "e", ['ё'] = "e", ['ж'] = "zh",
        ['з'] = "z", ['и'] = "i", ['й'] = "y", ['к'] = "k", ['л'] = "l", ['м'] = "m", ['н'] = "n", ['о'] = "o",
        ['п'] = "p", ['р'] = "r", ['с'] = "s", ['т'] = "t", ['у'] = "u", ['ф'] = "f", ['х'] = "kh", ['ц'] = "ts",
        ['ч'] = "ch", ['ш'] = "sh", ['щ'] = "shch", ['ъ'] = "", ['ы'] = "y", ['ь'] = "", ['э'] = "e", ['ю'] = "yu", ['я'] = "ya",
    };

    static string Translit(string s)
    {
        var sb = new StringBuilder();
        foreach (var ch in s) sb.Append(Tr.TryGetValue(ch, out var r) ? r : ch.ToString());
        return sb.ToString();
    }
}
