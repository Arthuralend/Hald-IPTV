using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace HaldIPTV;

/// Simple dark dialog base built in code.
public abstract class DarkDialog : Window
{
    protected readonly StackPanel Body = new() { Margin = new Thickness(20) };

    protected DarkDialog(string title, double width)
    {
        Title = title;
        Width = width;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Background = (Brush)Application.Current.Resources["Panel"];
        Content = Body;
        Body.Children.Add(new TextBlock { Text = title, FontSize = 20, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 14) });
    }

    protected TextBox Field(string label, string value)
    {
        Body.Children.Add(new TextBlock { Text = label, Foreground = (Brush)Application.Current.Resources["TextDim"], FontSize = 11, Margin = new Thickness(0, 8, 0, 3) });
        var tb = new TextBox { Text = value };
        Body.Children.Add(tb);
        return tb;
    }

    protected StackPanel Buttons(params (string text, bool accent, RoutedEventHandler click)[] buttons)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 18, 0, 0) };
        foreach (var (text, accent, click) in buttons)
        {
            var b = new Button { Content = text, Margin = new Thickness(8, 0, 0, 0), Style = (Style)Application.Current.Resources[accent ? "AccentButton" : "FlatButton"] };
            b.Click += click;
            row.Children.Add(b);
        }
        Body.Children.Add(row);
        return row;
    }
}

public class PlaylistDialog : DarkDialog
{
    public Playlist? Result { get; private set; }
    public bool Deleted { get; private set; }

    public PlaylistDialog(Playlist? p) : base(p == null ? Loc.T("Новый плейлист") : Loc.T("Плейлист"), 540)
    {
        var name = Field(Loc.T("Название"), p?.Name ?? "");
        var src = Field(Loc.T("Ссылка M3U / M3U8 или файл"), p?.Source ?? "");
        var pick = new Button { Content = Loc.T("Файл…"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 4, 0, 0), Style = (Style)Application.Current.Resources["FlatButton"] };
        pick.Click += (_, _) =>
        {
            var d = new OpenFileDialog { Filter = "M3U|*.m3u;*.m3u8|*.*|*.*" };
            if (d.ShowDialog(this) == true)
            {
                src.Text = d.FileName;
                if (name.Text.Length == 0) name.Text = System.IO.Path.GetFileNameWithoutExtension(d.FileName);
            }
        };
        Body.Children.Add(pick);
        var epg = Field(Loc.T("EPG (XMLTV), необязательно"), p?.EpgUrl ?? "");
        var ua = Field(Loc.T("User-Agent, необязательно"), p?.UserAgent ?? "");
        var hours = Field(Loc.T("Автообновление, часов"), (p?.AutoRefreshHours ?? 24).ToString());

        var buttons = new List<(string, bool, RoutedEventHandler)>();
        if (p != null) buttons.Add((Loc.T("Удалить"), false, (_, _) => { Deleted = true; DialogResult = true; }));
        buttons.Add((Loc.T("Отмена"), false, (_, _) => DialogResult = false));
        buttons.Add((Loc.T("Сохранить"), true, (_, _) =>
        {
            if (src.Text.Trim().Length == 0) return;
            var r = p ?? new Playlist();
            r.Name = name.Text.Trim().Length > 0 ? name.Text.Trim() : $"{Loc.T("Плейлист")} {DateTime.Now:dd.MM HH:mm}";
            r.Source = src.Text.Trim();
            r.EpgUrl = epg.Text.Trim().Length > 0 ? epg.Text.Trim() : null;
            r.UserAgent = ua.Text.Trim().Length > 0 ? ua.Text.Trim() : null;
            r.AutoRefreshHours = int.TryParse(hours.Text, out var h) && h > 0 ? h : 24;
            r.LastUpdated = null;
            Result = r;
            DialogResult = true;
        }));
        Buttons(buttons.ToArray());
        Loaded += (_, _) => (p == null ? src : name).Focus();
    }
}

public class SettingsDialog : DarkDialog
{
    public bool LogosChanged { get; private set; }

    public SettingsDialog(Settings s) : base(Loc.T("Настройки"), 520)
    {
        var cache = Field(Loc.T("Буфер сети, мс"), s.NetworkCaching.ToString());
        var ua = Field(Loc.T("User-Agent по умолчанию"), s.DefaultUserAgent);
        var useDb = new CheckBox { Content = Loc.T("Логотипы из базы iptv-org для каналов без значка"), IsChecked = s.UseLogoDb, Margin = new Thickness(0, 14, 0, 2) };
        var prefDb = new CheckBox { Content = Loc.T("Всегда предпочитать логотипы из базы"), IsChecked = s.PreferLogoDb };
        Body.Children.Add(useDb);
        Body.Children.Add(prefDb);
        var clear = new Button { Content = Loc.T("Очистить кэш логотипов"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 6, 0, 0), Style = (Style)Application.Current.Resources["FlatButton"] };
        clear.Click += (_, _) => { LogoCache.Clear(); LogosChanged = true; };
        Body.Children.Add(clear);
        var folder = Field(Loc.T("Папка записей"), s.RecordingsFolder);
        var pick = new Button { Content = Loc.T("Выбрать…"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 4, 0, 0), Style = (Style)Application.Current.Resources["FlatButton"] };
        pick.Click += (_, _) =>
        {
            var d = new OpenFolderDialog();
            if (d.ShowDialog(this) == true) folder.Text = d.FolderName;
        };
        Body.Children.Add(pick);

        Buttons(
            (Loc.T("Отмена"), false, (_, _) => DialogResult = false),
            (Loc.T("Сохранить"), true, (_, _) =>
            {
                s.NetworkCaching = int.TryParse(cache.Text, out var c) ? Math.Clamp(c, 200, 20000) : 1500;
                s.DefaultUserAgent = ua.Text.Trim();
                LogosChanged |= s.UseLogoDb != (useDb.IsChecked == true) || s.PreferLogoDb != (prefDb.IsChecked == true);
                s.UseLogoDb = useDb.IsChecked == true;
                s.PreferLogoDb = prefDb.IsChecked == true;
                s.RecordingsFolder = folder.Text.Trim();
                DialogResult = true;
            }));
    }
}
