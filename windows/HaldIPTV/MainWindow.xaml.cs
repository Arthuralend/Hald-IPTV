using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using LibVLCSharp.Shared;

namespace HaldIPTV;

public partial class MainWindow : Window
{
    // ── state ──
    public Settings Settings { get; private set; }
    public HashSet<string> Favorites { get; private set; }
    List<string> _recents;
    List<Playlist> _playlists;
    List<Channel> _channels = new();
    readonly Dictionary<string, ChannelVM> _vms = new();
    readonly ObservableCollection<ChannelVM> _visible = new();
    public EpgService Epg { get; } = new();
    public Channel? CurrentChannel { get; private set; }
    SidebarEntry? _section;

    // ── player ──
    readonly LibVLC _vlc;
    readonly MediaPlayer _mp;
    DateTime? _archiveStartUtc;       // null = live
    bool _recording;
    DateTime _recordStart;
    string? _currentUrl;
    bool _fullscreen;
    WindowState _prevState;
    WindowStyle _prevStyle;
    DateTime _lastMove = DateTime.Now;
    DateTime _toastUntil;
    readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };

    Playlist? SelectedPlaylist => PlaylistBox.SelectedItem as Playlist;
    bool IsLive => _archiveStartUtc == null;

    public MainWindow()
    {
        Settings = Storage.Load<Settings>("settings.json") ?? new Settings();
        Favorites = Storage.Load<HashSet<string>>("favorites.json") ?? new();
        _recents = Storage.Load<List<string>>("recents.json") ?? new();
        _playlists = Storage.Load<List<Playlist>>("playlists.json") ?? new();

        InitializeComponent();
        Localize();

        _vlc = new LibVLC("--no-video-title-show");
        _mp = new MediaPlayer(_vlc) { Volume = Settings.Volume, EnableHardwareDecoding = true };
        _mp.Playing += (_, _) => Dispatcher.BeginInvoke(() => { SetStatus(null); BtnPlay.Content = ""; UpdateAudioButton(); });
        _mp.Paused += (_, _) => Dispatcher.BeginInvoke(() => { SetStatus(Loc.T("Пауза")); BtnPlay.Content = ""; });
        _mp.Buffering += (_, e) => Dispatcher.BeginInvoke(() => { if (e.Cache < 100 && !_mp.IsPlaying) SetStatus(Loc.T("Подключение…")); else if (e.Cache >= 100) SetStatus(null); });
        _mp.EncounteredError += (_, _) => Dispatcher.BeginInvoke(() => SetStatus(Loc.T("Ошибка воспроизведения потока")));

        ChannelList.ItemsSource = _visible;
        VolumeSlider.Value = Settings.Volume;
        ApplyViewMode();

        Loaded += (_, _) =>
        {
            Video.MediaPlayer = _mp;
            var overlayWindow = Window.GetWindow(Overlay);
            if (overlayWindow != null && overlayWindow != this) overlayWindow.PreviewKeyDown += Window_PreviewKeyDown;
        };
        PreviewKeyDown += Window_PreviewKeyDown;
        Closing += (_, _) => { if (_recording) StopRecording(silentRestart: false); _mp.Stop(); SaveSettings(); };

        _timer.Tick += (_, _) => Tick();
        _timer.Start();

        PlaylistBox.ItemsSource = _playlists;
        var sel = _playlists.FirstOrDefault(p => p.Id == Settings.SelectedPlaylist) ?? _playlists.FirstOrDefault();
        if (sel != null) PlaylistBox.SelectedItem = sel;
        else Dispatcher.BeginInvoke(() => Add_Click(this, new RoutedEventArgs()), DispatcherPriority.ApplicationIdle);
        RebuildSidebar();
    }

    void Localize()
    {
        Title = Loc.T("Телевизор");
        BrandText.Text = Loc.T("Телевизор");
        LblPlaylist.Text = Loc.T("Плейлист").ToUpperInvariant();
        BtnAdd.Content = "+ " + Loc.T("Добавить");
        BtnEdit.ToolTip = Loc.T("Изменить");
        BtnRefresh.ToolTip = Loc.T("Обновить плейлист") + " (Ctrl+R)";
        BtnSettings.ToolTip = Loc.T("Настройки");
        SearchHint.Text = Loc.T("Поиск канала");
        PlaceholderText.Text = Loc.T("Выберите канал");
        MiClearRecents.Header = Loc.T("Очистить недавние");
        MiClearRecents2.Header = Loc.T("Очистить недавние");
        MiRemoveRecent.Header = Loc.T("Убрать из недавних");
        MiCopy.Header = Loc.T("Копировать ссылку");
        BtnClearRecents.ToolTip = Loc.T("Очистить недавние");
        BtnListMode.ToolTip = Loc.T("Список");
        BtnGridMode.ToolTip = Loc.T("Сетка");
        BtnBack5.ToolTip = Loc.T("Назад 5 мин") + " (Ctrl+Shift+←)";
        BtnBack30.ToolTip = Loc.T("Назад 30 с") + " (Ctrl+←)";
        BtnFwd30.ToolTip = Loc.T("Вперёд 30 с") + " (Ctrl+→)";
        BtnFwd5.ToolTip = Loc.T("Вперёд 5 мин") + " (Ctrl+Shift+→)";
        BtnStartOver.ToolTip = Loc.T("С начала передачи");
        BtnLive.ToolTip = Loc.T("Вернуться в прямой эфир") + " (Ctrl+L)";
        BtnPrev.ToolTip = Loc.T("Предыдущий канал") + " (Ctrl+↑)";
        BtnNext.ToolTip = Loc.T("Следующий канал") + " (Ctrl+↓)";
        BtnPlay.ToolTip = Loc.T("Пауза / Продолжить") + " (Space)";
        BtnRec.ToolTip = Loc.T("Запись") + " (Ctrl+Shift+R)";
        BtnFolder.ToolTip = Loc.T("Открыть папку записей");
        BtnAudio.ToolTip = Loc.T("Аудиодорожка");
        BtnMute.ToolTip = Loc.T("Без звука") + " (M)";
        BtnPin.ToolTip = Loc.T("Поверх всех окон");
        BtnFull.ToolTip = Loc.T("Полный экран канала") + " (F11 / Enter)";
    }

    // ═════════════════════ Playlists ═════════════════════

    void SavePlaylists() => Storage.Save("playlists.json", _playlists);
    void SaveSettings() => Storage.Save("settings.json", Settings);

    void PlaylistBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SelectedPlaylist is { } p)
        {
            Settings.SelectedPlaylist = p.Id;
            SaveSettings();
            _ = OpenPlaylist(p, false);
        }
    }

    async Task OpenPlaylist(Playlist p, bool force)
    {
        var cache = $"channels-{p.Id}.json";
        var stale = p.LastUpdated == null || DateTime.Now - p.LastUpdated > TimeSpan.FromHours(p.AutoRefreshHours);
        if (!force && !stale && Storage.Load<List<Channel>>(cache) is { Count: > 0 } cached)
        {
            Apply(cached);
            _ = LoadEpg(p, null);
            return;
        }
        Loading.Visibility = Visibility.Visible;
        try
        {
            string text;
            if (p.Source.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            {
                var data = await Net.GetBytes(p.Source, p.UserAgent);
                text = Decode(data);
            }
            else
            {
                var path = p.Source.StartsWith("file:") ? new Uri(p.Source).LocalPath : p.Source;
                text = Decode(await File.ReadAllBytesAsync(path));
            }
            var (list, epg) = M3UParser.Parse(text);
            if (list.Count == 0) throw new Exception(Loc.T("В плейлисте не найдено каналов"));
            Apply(list);
            Storage.Save(cache, list);
            p.LastUpdated = DateTime.Now;
            SavePlaylists();
            _ = LoadEpg(p, epg);
        }
        catch (Exception ex)
        {
            if (Storage.Load<List<Channel>>(cache) is { Count: > 0 } c) Apply(c);
            ShowToast(Loc.T("Не удалось загрузить плейлист: ") + ex.Message, 6);
        }
        finally { Loading.Visibility = Visibility.Collapsed; }
    }

    static string Decode(byte[] data)
    {
        try { return new UTF8Encoding(false, true).GetString(data); }
        catch
        {
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            return Encoding.GetEncoding(1251).GetString(data);
        }
    }

    async Task LoadEpg(Playlist p, string? fromPlaylist)
    {
        var key = $"epg-{p.Id}.txt";
        if (fromPlaylist != null) Storage.Save(key, fromPlaylist);
        var url = p.EpgUrl ?? fromPlaylist ?? Storage.Load<string>(key);
        if (string.IsNullOrEmpty(url) || Epg.LoadedFrom == url) return;
        try
        {
            await Epg.Load(url, p.UserAgent);
            foreach (var vm in _visible) vm.Refresh();
            UpdateNowNext();
        }
        catch { /* EPG is optional */ }
    }

    void Apply(List<Channel> list)
    {
        _channels = list;
        _vms.Clear();
        RebuildSidebar();
    }

    // ═════════════════════ Sidebar & list ═════════════════════

    void RebuildSidebar()
    {
        var prev = _section;
        var entries = new List<SidebarEntry>
        {
            new() { Kind = SidebarKind.All, Title = Loc.T("Все каналы"), Glyph = "", Count = _channels.Count },
            new() { Kind = SidebarKind.Favorites, Title = Loc.T("Избранное"), Glyph = "", Count = _channels.Count(c => Favorites.Contains(c.Id)) },
            new() { Kind = SidebarKind.Recents, Title = Loc.T("Недавние"), Glyph = "", Count = _recents.Count },
        };
        foreach (var g in _channels.GroupBy(c => c.Group))
            entries.Add(new() { Kind = SidebarKind.Group, Group = g.Key, Title = Loc.T(g.Key), Glyph = "", Count = g.Count() });
        SidebarList.ItemsSource = entries;
        SidebarList.SelectedItem = entries.FirstOrDefault(e => prev != null && e.Kind == prev.Kind && e.Group == prev.Group) ?? entries[0];
    }

    void SidebarList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SidebarList.SelectedItem is SidebarEntry s) { _section = s; RefreshList(); }
    }

    void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        SearchHint.Visibility = SearchBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        RefreshList();
    }

    void SearchBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || SearchBox.Text.Trim() != Preset.Code) return;
        e.Handled = true;
        SearchBox.Text = "";
        if (Preset.Url is not { } url) { ShowToast(Loc.T("Встроенный плейлист не настроен в этой сборке"), 5); return; }
        var existing = _playlists.FirstOrDefault(p => p.Source == url);
        if (existing == null)
        {
            existing = new Playlist { Name = Loc.T("Телевизор"), Source = url };
            _playlists.Add(existing);
            SavePlaylists();
            PlaylistBox.Items.Refresh();
        }
        PlaylistBox.SelectedItem = existing;
    }

    ChannelVM VM(Channel c)
    {
        if (!_vms.TryGetValue(c.Id, out var vm)) _vms[c.Id] = vm = new ChannelVM(c, this);
        return vm;
    }

    IEnumerable<Channel> SectionChannels()
    {
        var s = _section;
        if (s == null) return _channels;
        return s.Kind switch
        {
            SidebarKind.Favorites => _channels.Where(c => Favorites.Contains(c.Id)),
            SidebarKind.Recents => _recents.Select(id => _channels.FirstOrDefault(c => c.Id == id)).Where(c => c != null)!,
            SidebarKind.Group => _channels.Where(c => c.Group == s.Group),
            _ => _channels,
        };
    }

    void RefreshList()
    {
        var q = SearchBox.Text.Trim();
        var list = SectionChannels();
        if (q.Length > 0) list = list.Where(c => c.Name.Contains(q, StringComparison.CurrentCultureIgnoreCase));
        _visible.Clear();
        foreach (var c in list) _visible.Add(VM(c));
        SectionTitle.Text = _section?.Title ?? Loc.T("Все каналы");
        SectionCount.Text = $"{_visible.Count} {Loc.T("каналов")}";
        EmptyText.Text = _playlists.Count == 0 ? Loc.T("Добавьте плейлист") : Loc.T("Нет каналов");
        EmptyText.Visibility = _visible.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        BtnClearRecents.Visibility = _section?.Kind == SidebarKind.Recents && _recents.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    void ApplyViewMode()
    {
        ChannelList.ItemTemplate = (DataTemplate)Resources[Settings.GridMode ? "TileTemplate" : "RowTemplate"];
        ChannelList.ItemsPanel = (ItemsPanelTemplate)Resources[Settings.GridMode ? "GridPanel" : "ListPanel"];
        BtnListMode.Foreground = Settings.GridMode ? (System.Windows.Media.Brush)FindResource("TextDim") : (System.Windows.Media.Brush)FindResource("Orange");
        BtnGridMode.Foreground = Settings.GridMode ? (System.Windows.Media.Brush)FindResource("Orange") : (System.Windows.Media.Brush)FindResource("TextDim");
    }

    void ListMode_Click(object s, RoutedEventArgs e) { Settings.GridMode = false; ApplyViewMode(); SaveSettings(); }
    void GridMode_Click(object s, RoutedEventArgs e) { Settings.GridMode = true; ApplyViewMode(); SaveSettings(); }

    void ChannelList_Click(object sender, MouseButtonEventArgs e)
    {
        if (ChannelList.SelectedItem is ChannelVM vm) Play(vm.C);
    }

    void ChannelList_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && ChannelList.SelectedItem is ChannelVM vm) { Play(vm.C); e.Handled = true; }
    }

    void ChannelMenu_Opened(object sender, RoutedEventArgs e)
    {
        var vm = ChannelList.SelectedItem as ChannelVM;
        MiFav.Header = vm != null && Favorites.Contains(vm.C.Id) ? Loc.T("Убрать из избранного") : Loc.T("В избранное");
        var rec = _section?.Kind == SidebarKind.Recents ? Visibility.Visible : Visibility.Collapsed;
        MiRemoveRecent.Visibility = rec;
        MiClearRecents2.Visibility = rec;
    }

    void Fav_Click(object s, RoutedEventArgs e) { if (ChannelList.SelectedItem is ChannelVM vm) ToggleFavorite(vm.C); }
    void FavCurrent_Click(object s, RoutedEventArgs e) { if (CurrentChannel != null) ToggleFavorite(CurrentChannel); }

    void ToggleFavorite(Channel c)
    {
        if (!Favorites.Remove(c.Id)) Favorites.Add(c.Id);
        Storage.Save("favorites.json", Favorites);
        VM(c).Refresh();
        UpdateFavButton();
        RebuildSidebar();
    }

    void RemoveRecent_Click(object s, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is ChannelVM vm)
        {
            _recents.Remove(vm.C.Id);
            Storage.Save("recents.json", _recents);
            RebuildSidebar();
        }
    }

    void ClearRecents_Click(object s, RoutedEventArgs e)
    {
        _recents.Clear();
        Storage.Save("recents.json", _recents);
        RebuildSidebar();
    }

    void Copy_Click(object s, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is ChannelVM vm) Clipboard.SetText(vm.C.Url);
    }

    // ═════════════════════ Playlist dialogs ═════════════════════

    void Add_Click(object sender, RoutedEventArgs e)
    {
        var d = new PlaylistDialog(null) { Owner = this };
        if (d.ShowDialog() == true && d.Result != null)
        {
            _playlists.Add(d.Result);
            SavePlaylists();
            PlaylistBox.Items.Refresh();
            PlaylistBox.SelectedItem = d.Result;
        }
    }

    void Edit_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedPlaylist is not { } p) return;
        var d = new PlaylistDialog(p) { Owner = this };
        if (d.ShowDialog() != true) return;
        if (d.Deleted)
        {
            _playlists.Remove(p);
            Storage.Delete($"channels-{p.Id}.json");
            SavePlaylists();
            PlaylistBox.Items.Refresh();
            PlaylistBox.SelectedItem = _playlists.FirstOrDefault();
            if (_playlists.Count == 0) Apply(new());
        }
        else
        {
            SavePlaylists();
            PlaylistBox.Items.Refresh();
            _ = OpenPlaylist(p, true);
        }
    }

    void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedPlaylist is { } p) _ = OpenPlaylist(p, true);
    }

    void Settings_Click(object sender, RoutedEventArgs e)
    {
        var d = new SettingsDialog(Settings) { Owner = this };
        if (d.ShowDialog() == true)
        {
            SaveSettings();
            if (d.LogosChanged) { foreach (var vm in _vms.Values) vm.ResetLogo(); }
        }
    }

    // ═════════════════════ Playback ═════════════════════

    void Play(Channel c)
    {
        CurrentChannel = c;
        _archiveStartUtc = null;
        _recents.Remove(c.Id);
        _recents.Insert(0, c.Id);
        if (_recents.Count > 50) _recents.RemoveRange(50, _recents.Count - 50);
        Storage.Save("recents.json", _recents);
        Open(c.Url);
        foreach (var vm in _vms.Values) vm.Refresh();
        Controls.DataContext = VM(c);
        Placeholder.Visibility = Visibility.Collapsed;
        UpdateFavButton();
        UpdateNowNext();
        ShowControls();
    }

    void Open(string url, string? sout = null)
    {
        if (CurrentChannel is not { } c) return;
        if (_recording && sout == null) StopRecording(silentRestart: false);
        _currentUrl = url;
        var media = new Media(_vlc, new Uri(url));
        media.AddOption($":network-caching={Settings.NetworkCaching}");
        media.AddOption($":live-caching={Settings.NetworkCaching}");
        media.AddOption(":http-reconnect");
        var ua = c.UserAgent ?? SelectedPlaylist?.UserAgent ?? (Settings.DefaultUserAgent.Length > 0 ? Settings.DefaultUserAgent : null);
        if (ua != null) media.AddOption($":http-user-agent={ua}");
        if (sout != null) { media.AddOption(sout); media.AddOption(":sout-keep"); }
        _mp.Play(media);
        media.Dispose();
        SetStatus(Loc.T("Подключение…"));
        BtnAudio.Visibility = Visibility.Collapsed;
    }

    void Step(int d)
    {
        var list = _visible.Count > 0 ? _visible.Select(v => v.C).ToList() : _channels;
        if (list.Count == 0) return;
        var i = CurrentChannel == null ? -d : list.FindIndex(c => c.Id == CurrentChannel.Id);
        if (i < 0 && CurrentChannel != null) i = -d;
        Play(list[((i + d) % list.Count + list.Count) % list.Count]);
    }

    void Prev_Click(object s, RoutedEventArgs e) => Step(-1);
    void Next_Click(object s, RoutedEventArgs e) => Step(1);

    void Play_Click(object s, RoutedEventArgs e) => TogglePause();
    void TogglePause()
    {
        if (CurrentChannel == null) return;
        if (_mp.IsPlaying) _mp.Pause(); else _mp.Play();
    }

    void Volume_Changed(object s, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_mp == null) return;
        _mp.Volume = (int)e.NewValue;
        Settings.Volume = (int)e.NewValue;
    }

    void Mute_Click(object s, RoutedEventArgs e)
    {
        _mp.Mute = !_mp.Mute;
        BtnMute.Content = _mp.Mute ? "" : "";
    }

    void Pin_Click(object s, RoutedEventArgs e)
    {
        Topmost = !Topmost;
        BtnPin.Content = Topmost ? "" : "";
    }

    void UpdateAudioButton()
    {
        var tracks = _mp.AudioTrackDescription.Where(t => t.Id >= 0).ToArray();
        BtnAudio.Visibility = tracks.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
    }

    void Audio_Click(object s, RoutedEventArgs e)
    {
        var menu = new ContextMenu();
        foreach (var t in _mp.AudioTrackDescription.Where(t => t.Id >= 0))
        {
            var id = t.Id;
            var mi = new MenuItem { Header = t.Name, IsCheckable = true, IsChecked = _mp.AudioTrack == id };
            mi.Click += (_, _) => _mp.SetAudioTrack(id);
            menu.Items.Add(mi);
        }
        menu.PlacementTarget = BtnAudio;
        menu.IsOpen = true;
    }

    void UpdateFavButton()
    {
        BtnFav.Content = CurrentChannel != null && Favorites.Contains(CurrentChannel.Id) ? "" : "";
        BtnFav.Foreground = CurrentChannel != null && Favorites.Contains(CurrentChannel.Id)
            ? (System.Windows.Media.Brush)FindResource("Orange") : (System.Windows.Media.Brush)FindResource("Text");
    }

    void UpdateNowNext()
    {
        if (CurrentChannel == null) return;
        var (now, next) = Epg.NowNext(CurrentChannel);
        NowText.Text = now != null ? $"{now.Start.ToLocalTime():HH:mm}–{now.Stop.ToLocalTime():HH:mm}  {now.Title}" : "";
        NowProgress.Visibility = now != null ? Visibility.Visible : Visibility.Collapsed;
        NowProgress.Value = now?.Progress(DateTime.UtcNow) ?? 0;
        NextText.Text = next != null ? $"{Loc.T("Далее:")} {next.Start.ToLocalTime():HH:mm} {next.Title}" : "";
        BtnStartOver.Visibility = now != null && CurrentChannel.HasArchive ? Visibility.Visible : Visibility.Collapsed;
    }

    void SetStatus(string? text)
    {
        StatusBox.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        StatusText.Text = text ?? "";
    }

    void ShowToast(string text, int seconds = 3)
    {
        ToastText.Text = text;
        ToastBox.Visibility = Visibility.Visible;
        _toastUntil = DateTime.Now.AddSeconds(seconds);
    }

    // ═════════════════════ Archive / rewind ═════════════════════

    DateTime PositionUtc => _archiveStartUtc is { } a ? a.AddMilliseconds(Math.Max(0, _mp.Time)) : DateTime.UtcNow;

    void Rewind(double seconds)
    {
        if (CurrentChannel is not { } c) return;
        var target = PositionUtc.AddSeconds(-seconds);
        if (c.HasArchive)
        {
            var limit = DateTime.UtcNow.AddDays(-(c.CatchupDays ?? 7));
            PlayArchive(c, target < limit ? limit : target);
        }
        else if (_mp.IsSeekable) _mp.Time = Math.Max(0, _mp.Time - (long)(seconds * 1000));
        else ShowToast(Loc.T("Архив недоступен для этого канала"));
    }

    void Forward(double seconds)
    {
        if (CurrentChannel is not { } c) return;
        if (IsLive)
        {
            if (_mp.IsSeekable) _mp.Time += (long)(seconds * 1000);
            return;
        }
        var target = PositionUtc.AddSeconds(seconds);
        if (target > DateTime.UtcNow.AddSeconds(-15)) GoLive(); else PlayArchive(c, target);
    }

    void GoLive() { if (CurrentChannel != null) { _archiveStartUtc = null; Open(CurrentChannel.Url); } }

    void PlayArchive(Channel c, DateTime startUtc)
    {
        var url = Catchup.Url(c, startUtc, DateTime.UtcNow);
        if (url == null) { ShowToast(Loc.T("Архив недоступен для этого канала")); return; }
        _archiveStartUtc = startUtc;
        Open(url);
    }

    void Back5_Click(object s, RoutedEventArgs e) => Rewind(300);
    void Back30_Click(object s, RoutedEventArgs e) => Rewind(30);
    void Fwd30_Click(object s, RoutedEventArgs e) => Forward(30);
    void Fwd5_Click(object s, RoutedEventArgs e) => Forward(300);
    void Live_Click(object s, RoutedEventArgs e) => GoLive();
    void StartOver_Click(object s, RoutedEventArgs e)
    {
        if (CurrentChannel is { } c && Epg.NowNext(c).now is { } now) PlayArchive(c, now.Start);
    }

    // ═════════════════════ Recording ═════════════════════

    string RecordingsFolder => Settings.RecordingsFolder.Length > 0
        ? Settings.RecordingsFolder
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), Loc.T("Телевизор"));

    void Rec_Click(object s, RoutedEventArgs e) => ToggleRecording();

    void ToggleRecording()
    {
        if (_recording) StopRecording(silentRestart: true); else StartRecording();
    }

    void StartRecording()
    {
        if (CurrentChannel is not { } c || _currentUrl == null) return;
        Directory.CreateDirectory(RecordingsFolder);
        var safe = string.Concat(c.Name.Select(ch => Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch));
        var file = Path.Combine(RecordingsFolder, $"{safe} {DateTime.Now:yyyy-MM-dd HH-mm-ss}.ts").Replace("\\", "/");
        _recording = true;
        _recordStart = DateTime.Now;
        // the same stream is shown and written to disk at once (one connection to the provider)
        Open(_currentUrl, $":sout=#duplicate{{dst=display,dst=std{{access=file,mux=ts,dst='{file}'}}}}");
        BtnRec.Content = "";
        BtnRec.Foreground = System.Windows.Media.Brushes.Red;
        BtnRec.ToolTip = Loc.T("Остановить запись");
    }

    void StopRecording(bool silentRestart)
    {
        if (!_recording) return;
        _recording = false;
        BtnRec.Content = "";
        BtnRec.Foreground = (System.Windows.Media.Brush)FindResource("Text");
        BtnRec.ToolTip = Loc.T("Запись");
        RecBadge.Visibility = Visibility.Collapsed;
        ShowToast(Loc.T("Запись сохранена"));
        if (silentRestart && _currentUrl != null) Open(_currentUrl);
    }

    void Folder_Click(object s, RoutedEventArgs e)
    {
        Directory.CreateDirectory(RecordingsFolder);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{RecordingsFolder}\"") { UseShellExecute = true });
    }

    // ═════════════════════ Full screen ═════════════════════

    void Full_Click(object s, RoutedEventArgs e) => ToggleFullscreen();

    void ToggleFullscreen()
    {
        if (!_fullscreen)
        {
            _prevState = WindowState;
            _prevStyle = WindowStyle;
            ColSidebar.Width = new GridLength(0);
            ColList.Width = new GridLength(0);
            ColSplit.Width = new GridLength(0);
            SidebarPanel.Visibility = ListPanel.Visibility = Splitter.Visibility = Visibility.Collapsed;
            WindowState = WindowState.Normal;      // needed so Maximized covers the taskbar
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            WindowState = WindowState.Maximized;
            BtnFull.Content = "";
            _fullscreen = true;
        }
        else
        {
            WindowStyle = _prevStyle;
            ResizeMode = ResizeMode.CanResize;
            WindowState = _prevState;
            ColSidebar.Width = new GridLength(232);
            ColList.Width = new GridLength(370);
            ColSplit.Width = new GridLength(4);
            SidebarPanel.Visibility = ListPanel.Visibility = Splitter.Visibility = Visibility.Visible;
            BtnFull.Content = "";
            _fullscreen = false;
        }
        ShowControls();
    }

    // ═════════════════════ Overlay / timer / keys ═════════════════════

    void Overlay_MouseMove(object sender, MouseEventArgs e) => ShowControls();

    void Overlay_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2) { ToggleFullscreen(); e.Handled = true; }
        else ShowControls();
    }

    void ShowControls()
    {
        _lastMove = DateTime.Now;
        if (CurrentChannel != null) Controls.Visibility = Visibility.Visible;
        Overlay.Cursor = null;
    }

    void Tick()
    {
        if (CurrentChannel != null)
        {
            var idle = (DateTime.Now - _lastMove).TotalSeconds > 3;
            var hide = idle && _mp.IsPlaying && !Controls.IsMouseOver;
            Controls.Visibility = hide ? Visibility.Collapsed : Visibility.Visible;
            if (_fullscreen) Overlay.Cursor = hide ? Cursors.None : null;

            if (IsLive)
            {
                LiveText.Text = Loc.T("ЭФИР");
                LiveDot.Fill = (System.Windows.Media.Brush)FindResource("Orange");
                PosText.Text = "";
            }
            else
            {
                var behind = (int)Math.Max(0, (DateTime.UtcNow - PositionUtc).TotalSeconds);
                var ts = TimeSpan.FromSeconds(behind);
                LiveText.Text = "−" + (behind >= 3600 ? ts.ToString(@"h\:mm\:ss") : ts.ToString(@"mm\:ss"));
                LiveDot.Fill = System.Windows.Media.Brushes.Gray;
                PosText.Text = PositionUtc.ToLocalTime().ToString("HH:mm");
            }
            BtnFwd30.IsEnabled = BtnFwd5.IsEnabled = !IsLive || _mp.IsSeekable;
            UpdateNowNext();
        }
        if (_recording)
        {
            RecBadge.Visibility = Visibility.Visible;
            RecText.Text = "REC " + (DateTime.Now - _recordStart).ToString(@"hh\:mm\:ss");
        }
        if (ToastBox.Visibility == Visibility.Visible && DateTime.Now > _toastUntil) ToastBox.Visibility = Visibility.Collapsed;
    }

    void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        bool ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        bool shift = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift);
        bool typing = Keyboard.FocusedElement is TextBox;

        switch (e.Key)
        {
            case Key.F11: ToggleFullscreen(); break;
            case Key.Escape when _fullscreen: ToggleFullscreen(); break;
            case Key.Enter when !typing && !(Keyboard.FocusedElement is ListBoxItem) && CurrentChannel != null: ToggleFullscreen(); break;
            case Key.Space when !typing: TogglePause(); break;
            case Key.M when !typing && !ctrl: Mute_Click(this, e); break;
            case Key.Up when ctrl: Step(-1); break;
            case Key.Down when ctrl: Step(1); break;
            case Key.Left when ctrl: Rewind(shift ? 300 : 30); break;
            case Key.Right when ctrl: Forward(shift ? 300 : 30); break;
            case Key.L when ctrl: GoLive(); break;
            case Key.R when ctrl && shift: ToggleRecording(); break;
            case Key.R when ctrl: Refresh_Click(this, e); break;
            case Key.F when ctrl: SearchBox.Focus(); break;
            default: return;
        }
        e.Handled = true;
        ShowControls();
    }
}
