import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: PlayerController
    @State private var sidebar: SidebarItem? = .all
    @State private var search = ""
    @State private var showAdd = false
    @State private var editing: Playlist?
    @AppStorage("gridMode") private var gridMode = false

    var visible: [Channel] { store.channels(for: sidebar ?? .all, search: search) }

    var body: some View {
        Group {
            if store.videoOnly {
                PlayerPane(next: { step(1) }, prev: { step(-1) })
                    .ignoresSafeArea()
            } else {
                split
            }
        }
        .sheet(isPresented: $showAdd) { PlaylistEditor(playlist: nil) }
        .sheet(item: $editing) { PlaylistEditor(playlist: $0) }
        .overlay(alignment: .bottom) {
            if let err = store.errorMessage {
                Text(err).padding(10).background(.red.opacity(0.85), in: Capsule()).foregroundStyle(.white)
                    .padding().onTapGesture { store.errorMessage = nil }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .channelStep)) { n in
            step((n.object as? Int) ?? 1)
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { _ in
            store.videoOnly = false
        }
        .onChange(of: store.selectedPlaylistID) { _ in sidebar = .all }
        .onAppear { if store.playlists.isEmpty { showAdd = true } }
    }

    var split: some View {
        NavigationSplitView {
            SidebarView(selection: $sidebar, showAdd: $showAdd, editing: $editing)
                .navigationSplitViewColumnWidth(min: 200, ideal: 240)
        } content: {
            ChannelListView(channels: visible, gridMode: gridMode, isRecents: sidebar == .recents, play: play)
                .navigationTitle((sidebar ?? .all).title)
                .navigationSubtitle(L("%d каналов", visible.count))
                .searchable(text: $search, placement: .toolbar, prompt: L("Поиск канала"))
                .navigationSplitViewColumnWidth(min: 280, ideal: 360)
                .toolbar {
                    ToolbarItemGroup {
                        if sidebar == .recents && !store.recents.isEmpty {
                            Button { store.clearRecents() } label: { Image(systemName: "trash") }
                                .help(L("Очистить недавние"))
                        }
                        Picker(L("Вид"), selection: $gridMode) {
                            Image(systemName: "list.bullet").tag(false)
                            Image(systemName: "square.grid.2x2").tag(true)
                        }.pickerStyle(.segmented)
                        Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }
                            .help(L("Обновить плейлист"))
                    }
                }
        } detail: {
            PlayerPane(next: { step(1) }, prev: { step(-1) })
        }
    }

    func play(_ c: Channel) {
        store.markPlayed(c)
        player.play(c, playlistUA: store.selectedPlaylist?.userAgent)
    }

    func step(_ d: Int) {
        let list = visible.isEmpty ? store.channels : visible
        guard !list.isEmpty else { return }
        let i = store.current.flatMap { c in list.firstIndex(of: c) } ?? -d
        play(list[(i + d + list.count) % list.count])
    }
}

extension Notification.Name {
    static let channelStep = Notification.Name("channelStep")
}

struct SidebarView: View {
    @EnvironmentObject var store: AppStore
    @Binding var selection: SidebarItem?
    @Binding var showAdd: Bool
    @Binding var editing: Playlist?

    var body: some View {
        List(selection: $selection) {
            Section(L("Плейлист")) {
                Picker("", selection: Binding(get: { store.selectedPlaylistID }, set: { store.select($0) })) {
                    ForEach(store.playlists) { Text($0.name).tag(Optional($0.id)) }
                }
                .labelsHidden()
                HStack {
                    Button { showAdd = true } label: { Label(L("Добавить"), systemImage: "plus") }
                    Spacer()
                    if let p = store.selectedPlaylist {
                        Button { editing = p } label: { Image(systemName: "pencil") }.help(L("Изменить"))
                    }
                }
                .buttonStyle(.borderless)
                if store.isLoading { ProgressView().controlSize(.small) }
            }
            Section(L("Разделы")) {
                Label(L("Все каналы"), systemImage: "tv").badge(store.channels.count).tag(SidebarItem.all)
                Label(L("Избранное"), systemImage: "star.fill").badge(store.favorites.count).tag(SidebarItem.favorites)
                Label(L("Недавние"), systemImage: "clock").badge(store.recents.count).tag(SidebarItem.recents)
                    .contextMenu { Button(L("Очистить недавние")) { store.clearRecents() } }
            }
            Section(L("Группы")) {
                ForEach(store.groups, id: \.name) { g in
                    Label(L(g.name), systemImage: "folder").badge(g.count).tag(SidebarItem.group(g.name))
                }
            }
        }
        .listStyle(.sidebar)
    }
}

struct ChannelListView: View {
    @EnvironmentObject var store: AppStore
    let channels: [Channel]
    let gridMode: Bool
    let isRecents: Bool
    let play: (Channel) -> Void

    var body: some View {
        Group {
            if channels.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "tv.slash").font(.largeTitle).foregroundStyle(.secondary)
                    Text(store.playlists.isEmpty ? L("Добавьте плейлист") : L("Нет каналов")).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if gridMode {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 12) {
                        ForEach(channels) { c in
                            VStack(spacing: 6) {
                                LogoView(channel: c, size: 72)
                                Text(c.name).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity, minHeight: 120)
                            .padding(6)
                            .background(store.current == c ? Color.accentColor.opacity(0.25) : Color.gray.opacity(0.08),
                                        in: RoundedRectangle(cornerRadius: 10))
                            .contentShape(Rectangle())
                            .onTapGesture { play(c) }
                            .contextMenu { menu(c) }
                        }
                    }.padding(12)
                }
            } else {
                List(channels) { c in
                    ChannelRow(channel: c, isCurrent: store.current == c)
                        .contentShape(Rectangle())
                        .onTapGesture { play(c) }
                        .contextMenu { menu(c) }
                }
            }
        }
    }

    @ViewBuilder func menu(_ c: Channel) -> some View {
        Button(store.favorites.contains(c.id) ? L("Убрать из избранного") : L("В избранное")) { store.toggleFavorite(c) }
        if isRecents {
            Button(L("Убрать из недавних")) { store.removeFromRecents(c) }
            Button(L("Очистить недавние")) { store.clearRecents() }
        }
        Button(L("Копировать ссылку")) {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(c.url, forType: .string)
        }
    }
}

struct ChannelRow: View {
    @EnvironmentObject var store: AppStore
    let channel: Channel
    let isCurrent: Bool

    var body: some View {
        let _ = store.epgVersion
        let (now, _) = store.epg.nowNext(for: channel)
        HStack(spacing: 10) {
            LogoView(channel: channel, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(channel.name).fontWeight(isCurrent ? .bold : .regular).lineLimit(1)
                    if store.favorites.contains(channel.id) {
                        Image(systemName: "star.fill").foregroundStyle(.yellow).font(.caption2)
                    }
                }
                if let now {
                    Text(now.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    ProgressView(value: now.progress()).controlSize(.mini).tint(.accentColor)
                }
            }
            Spacer()
            if isCurrent { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.accentColor) }
        }
        .padding(.vertical, 2)
    }
}

struct PlayerPane: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: PlayerController
    let next: () -> Void
    let prev: () -> Void
    @State private var hover = false
    @State private var lastMove = Date()
    @State private var onTop = false

    var showControls: Bool {
        guard store.current != nil else { return false }
        if !player.isPlaying { return true }
        if store.videoOnly { return Date().timeIntervalSince(lastMove) < 3 }
        return hover
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            ZStack {
                Color.black
                VLCVideoContainer(controller: player)
                if store.current == nil {
                    VStack(spacing: 10) {
                        Image(systemName: "play.tv").font(.system(size: 54))
                        Text(L("Выберите канал"))
                    }.foregroundStyle(.gray)
                }
                if player.isBuffering { ProgressView().controlSize(.large).tint(.white) }
                if !player.statusText.isEmpty && !player.isBuffering {
                    Text(player.statusText).foregroundStyle(.white).padding(8).background(.black.opacity(0.6), in: Capsule())
                }
                VStack {
                    HStack {
                        if player.isRecording, let start = player.recordStart { recBadge(start) }
                        Spacer()
                        if let t = player.toast {
                            Text(t).font(.callout).foregroundStyle(.white).padding(8)
                                .background(.black.opacity(0.7), in: Capsule())
                        }
                    }.padding(12)
                    Spacer()
                    if showControls, let c = store.current { controls(c) }
                }
            }
        }
        .onHover { hover = $0 }
        .onContinuousHover { phase in
            if case .active = phase { lastMove = Date() }
        }
        .onTapGesture(count: 2) { FullScreen.channel(store) }
        .onExitCommand { if store.videoOnly { FullScreen.isFull ? FullScreen.channel(store) : (store.videoOnly = false) } }
        .frame(minWidth: 420, minHeight: 260)
    }

    func recBadge(_ start: Date) -> some View {
        let s = Int(Date().timeIntervalSince(start))
        let text = String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        return HStack(spacing: 6) {
            Circle().fill(.red).frame(width: 10, height: 10)
            Text("REC \(text)").font(.system(.callout, design: .monospaced).bold())
        }
        .foregroundStyle(.white).padding(.horizontal, 10).padding(.vertical, 6)
        .background(.black.opacity(0.6), in: Capsule())
    }

    func controls(_ c: Channel) -> some View {
        let _ = store.epgVersion
        let (now, nextP) = store.epg.nowNext(for: c)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                LogoView(channel: c, size: 34)
                VStack(alignment: .leading) {
                    Text(c.name).font(.headline)
                    if let now {
                        Text("\(time(now.start))–\(time(now.stop))  \(now.title)").font(.caption)
                        ProgressView(value: now.progress()).frame(maxWidth: 260).tint(.white)
                    }
                    if let nextP { Text("\(L("Далее:")) \(time(nextP.start)) \(nextP.title)").font(.caption2).opacity(0.7) }
                }
                Spacer()
            }
            HStack(spacing: 14) {
                Button(action: prev) { Image(systemName: "backward.end.fill") }.help(L("Предыдущий канал (⌘↑)"))
                Button { player.togglePause() } label: { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill") }
                Button(action: next) { Image(systemName: "forward.end.fill") }.help(L("Следующий канал (⌘↓)"))
                Button { store.toggleFavorite(c) } label: {
                    Image(systemName: store.favorites.contains(c.id) ? "star.fill" : "star")
                }
                Button { player.toggleRecording() } label: {
                    Image(systemName: player.isRecording ? "stop.circle.fill" : "record.circle")
                        .foregroundStyle(player.isRecording ? .red : .white)
                }.help(player.isRecording ? L("Остановить запись") : L("Запись"))
                Button { player.showRecordings() } label: { Image(systemName: "folder") }
                    .help(L("Открыть папку записей"))
                Spacer()
                if player.audioTracks.count > 1 {
                    Menu {
                        ForEach(player.audioTracks, id: \.index) { t in
                            Button((t.index == player.currentAudioTrack ? "✓ " : "") + t.name) { player.setAudioTrack(t.index) }
                        }
                    } label: { Image(systemName: "waveform") }.menuStyle(.borderlessButton).frame(width: 40)
                        .help(L("Аудиодорожка"))
                }
                Button { player.muted.toggle() } label: {
                    Image(systemName: player.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                }
                Slider(value: $player.volume, in: 0...150).frame(width: 110)
                if !store.videoOnly {
                    Button {
                        onTop.toggle()
                        FullScreen.window?.level = onTop ? .floating : .normal
                    } label: { Image(systemName: onTop ? "pin.fill" : "pin") }.help(L("Поверх всех окон"))
                }
                Button { store.videoOnly.toggle() } label: {
                    Image(systemName: store.videoOnly ? "sidebar.left" : "rectangle.expand.vertical")
                }.help(store.videoOnly ? L("Выйти из режима видео") : L("Только видео в окне"))
                Button { FullScreen.channel(store) } label: {
                    Image(systemName: FullScreen.isFull && store.videoOnly
                          ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                }.help(L("Полный экран канала") + " (⌘↩)")
            }
            .buttonStyle(.borderless)
            .font(.title3)
        }
        .foregroundStyle(.white)
        .padding(14)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
    }

    func time(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f.string(from: d)
    }
}

struct PlaylistEditor: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    let playlist: Playlist?
    @State private var name = ""
    @State private var source = ""
    @State private var epg = ""
    @State private var ua = ""
    @State private var hours = 24
    @State private var pickFile = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(playlist == nil ? L("Новый плейлист") : L("Плейлист")).font(.title2.bold())
            Form {
                TextField(L("Название"), text: $name)
                HStack {
                    TextField(L("Ссылка M3U / M3U8"), text: $source)
                    Button(L("Файл…")) { pickFile = true }
                }
                TextField(L("EPG (XMLTV), необязательно"), text: $epg)
                TextField(L("User-Agent, необязательно"), text: $ua)
                Stepper(L("Автообновление: каждые %d ч", hours), value: $hours, in: 1...168)
            }
            HStack {
                if let playlist {
                    Button(L("Удалить"), role: .destructive) { store.removePlaylist(playlist); dismiss() }
                }
                Spacer()
                Button(L("Отмена")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(L("Сохранить")) { save() }.keyboardShortcut(.defaultAction)
                    .disabled(source.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 520)
        .fileImporter(isPresented: $pickFile, allowedContentTypes: [.data, .plainText]) { r in
            if case .success(let url) = r {
                _ = url.startAccessingSecurityScopedResource()
                source = url.absoluteString
                if name.isEmpty { name = url.deletingPathExtension().lastPathComponent }
            }
        }
        .onAppear {
            if let p = playlist {
                name = p.name; source = p.source; epg = p.epgURL ?? ""; ua = p.userAgent ?? ""; hours = p.autoRefreshHours
            }
        }
    }

    func save() {
        let src = source.trimmingCharacters(in: .whitespacesAndNewlines)
        if var p = playlist {
            p.name = name; p.source = src; p.epgURL = epg.isEmpty ? nil : epg
            p.userAgent = ua.isEmpty ? nil : ua; p.autoRefreshHours = hours
            store.updatePlaylist(p)
        } else {
            store.addPlaylist(name: name, source: src, epg: epg, userAgent: ua)
        }
        dismiss()
    }
}

struct SettingsView: View {
    @AppStorage("networkCaching") var networkCaching: Int = 1500
    @AppStorage("defaultUserAgent") var defaultUserAgent: String = ""
    @AppStorage("useLogoDB") var useLogoDB = true
    @AppStorage("preferLogoDB") var preferLogoDB = false
    @AppStorage("recordingsFolder") var recordingsFolder = ""
    @State private var pick = false

    var body: some View {
        Form {
            Stepper(L("Буфер сети: %d мс", networkCaching), value: $networkCaching, in: 300...10000, step: 100)
            TextField(L("User-Agent по умолчанию"), text: $defaultUserAgent)
            Divider()
            Toggle(L("Логотипы из базы iptv-org для каналов без значка"), isOn: $useLogoDB)
            Toggle(L("Всегда предпочитать логотипы из базы"), isOn: $preferLogoDB)
            Button(L("Очистить кэш логотипов")) { Task { await LogoCache.shared.clear() } }
            Divider()
            HStack {
                Text(L("Папка записей") + ":")
                Text(PlayerController.recordingsFolder.path).lineLimit(1).truncationMode(.middle).foregroundStyle(.secondary)
                Button(L("Выбрать…")) { pick = true }
            }
        }
        .padding(20)
        .frame(width: 520)
        .fileImporter(isPresented: $pick, allowedContentTypes: [.folder]) { r in
            if case .success(let url) = r { recordingsFolder = url.path }
        }
    }
}
