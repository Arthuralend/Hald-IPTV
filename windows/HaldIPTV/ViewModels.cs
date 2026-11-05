using System.Collections.Concurrent;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace HaldIPTV;

public abstract class Notifier : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    protected void Raise([CallerMemberName] string? n = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
    protected void RaiseAll() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
}

public enum SidebarKind { All, Favorites, Recents, Group }

public class SidebarEntry
{
    public SidebarKind Kind { get; init; }
    public string Group { get; init; } = "";
    public string Title { get; init; } = "";
    public string Glyph { get; init; } = "";
    public int Count { get; init; }
    public string CountText => Count > 0 ? Count.ToString() : "";
    public FontWeight Weight => Kind == SidebarKind.Group ? FontWeights.Normal : FontWeights.SemiBold;
}

public class ChannelVM : Notifier
{
    public Channel C { get; }
    readonly MainWindow _w;
    ImageSource? _logo;
    bool _requested;

    public ChannelVM(Channel c, MainWindow w) { C = c; _w = w; }

    public string Name => C.Name;

    public ImageSource? Logo
    {
        get
        {
            if (!_requested) { _requested = true; _ = LoadLogo(); }
            return _logo;
        }
    }

    async Task LoadLogo()
    {
        var s = _w.Settings;
        ImageSource? img = null;
        if (!s.PreferLogoDb && C.Logo != null) img = await LogoCache.Get(C.Logo);
        if (img == null && (s.UseLogoDb || s.PreferLogoDb))
        {
            var u = await LogoDatabase.Find(C);
            if (u != null) img = await LogoCache.Get(u);
        }
        if (img == null && s.PreferLogoDb && C.Logo != null) img = await LogoCache.Get(C.Logo);
        if (img != null)
        {
            _logo = img;
            Raise(nameof(Logo)); Raise(nameof(PlaceholderVisibility));
        }
    }

    public void ResetLogo() { _requested = false; _logo = null; Raise(nameof(Logo)); Raise(nameof(PlaceholderVisibility)); }

    public Visibility PlaceholderVisibility => _logo == null ? Visibility.Visible : Visibility.Collapsed;

    public string Initials =>
        string.Concat(C.Name.Split(' ', StringSplitOptions.RemoveEmptyEntries).Take(2).Select(w => char.ToUpper(w[0])));

    static readonly Brush[] Palette =
    {
        Frozen("#F2800A"), Frozen("#D96200"), Frozen("#5C9EBF"), Frozen("#6B6E75"),
    };

    static Brush Frozen(string hex)
    {
        var b = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        b.Freeze();
        return b;
    }

    public Brush PlaceholderBrush
    {
        get
        {
            int h = 0;
            foreach (var ch in C.Name) h = (h * 31 + ch) & 0xffff;
            return Palette[h % Palette.Length];
        }
    }

    public bool IsFavorite => _w.Favorites.Contains(C.Id);
    public bool IsCurrent => _w.CurrentChannel?.Id == C.Id;

    public Visibility FavVisibility => IsFavorite ? Visibility.Visible : Visibility.Collapsed;
    public Visibility CurrentVisibility => IsCurrent ? Visibility.Visible : Visibility.Collapsed;
    public FontWeight NameWeight => IsCurrent ? FontWeights.Bold : FontWeights.Normal;
    public Brush TileBackground => IsCurrent ? (Brush)Application.Current.Resources["OrangeSoft"] : (Brush)Application.Current.Resources["Card"];
    public Brush TileBorder => IsCurrent ? (Brush)Application.Current.Resources["Orange"] : (Brush)Application.Current.Resources["Line"];

    public string NowTitle => _w.Epg.NowNext(C).now?.Title ?? "";
    public double Progress => _w.Epg.NowNext(C).now?.Progress(DateTime.UtcNow) ?? 0;
    public Visibility EpgVisibility => NowTitle.Length > 0 ? Visibility.Visible : Visibility.Collapsed;

    public void Refresh() => RaiseAll();
}

public static class LogoCache
{
    static readonly ConcurrentDictionary<string, ImageSource?> Memory = new();
    static readonly SemaphoreSlim Gate = new(8);
    static readonly string Dir = Path.Combine(Storage.CacheDir, "logos");

    static LogoCache() { Directory.CreateDirectory(Dir); }

    static string Key(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s)));

    public static async Task<ImageSource?> Get(string url)
    {
        if (Memory.TryGetValue(url, out var m)) return m;
        var file = Path.Combine(Dir, Key(url));
        byte[]? data = null;
        if (File.Exists(file)) data = await File.ReadAllBytesAsync(file);
        else
        {
            await Gate.WaitAsync();
            try
            {
                data = await Net.GetBytes(url, "Mozilla/5.0");
                await File.WriteAllBytesAsync(file, data);
            }
            catch { data = null; }
            finally { Gate.Release(); }
        }
        ImageSource? img = null;
        if (data != null)
        {
            try
            {
                var bi = new BitmapImage();
                bi.BeginInit();
                bi.CacheOption = BitmapCacheOption.OnLoad;
                bi.DecodePixelWidth = 144;
                bi.StreamSource = new MemoryStream(data);
                bi.EndInit();
                bi.Freeze();
                img = bi;
            }
            catch { img = null; }
        }
        Memory[url] = img;
        return img;
    }

    public static void Clear()
    {
        Memory.Clear();
        try { Directory.Delete(Dir, true); } catch { }
        Directory.CreateDirectory(Dir);
    }
}
