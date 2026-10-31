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
        NavigationSplitView {
            SidebarView(selection: $sidebar, showAdd: $showAdd, editing: $editing)
                .navigationSplitViewColumnWidth(min: 200, ideal: 240)
        } content: {
            ChannelListView(channels: visible, gridMode: gridMode, play: play)
                .navigationTitle((sidebar ?? .all).title)
                .navigationSubtitle("\(visible.count) каналов")
                .searchable(text: $search, placement: .toolbar, prompt: "Поиск канала")
                .navigationSplitViewColumnWidth(min: 280, ideal: 360)
                .toolbar {
                    ToolbarItemGroup {
                        Picker("Вид", selection: $gridMode) {
                            Image(systemName: "list.bullet").tag(false)
                            Image(systemName: "square.grid.2x2").tag(true)
                        }.pickerStyle(.segmented)
                        Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }
                            .help("Обновить плейлист")
                    }
                }
        } detail: {
            PlayerPane(next: { step(1) }, prev: { step(-1) })
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
        .onChange(of: store.selectedPlaylistID) { _ in sidebar = .all }
        .onAppear { if store.playlists.isEmpty { showAdd = true } }
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
            Section("Плейлист") {
                Picker("", selection: Binding(get: { store.selectedPlaylistID }, set: { store.select($0) })) {
                    ForEach(store.playlists) { Text($0.name).tag(Optional($0.id)) }
                }
                .labelsHidden()
                HStack {
                    Button { showAdd = true } label: { Label("Добавить", systemImage: "plus") }
                    Spacer()
                    if let p = store.selectedPlaylist {
                        Button { editing = p } label: { Image(systemName: "pencil") }.help("Изменить")
                    }
                }
                .buttonStyle(.borderless)
                if store.isLoading { ProgressView().controlSize(.small) }
            }
            Section("Разделы") {
                Label("Все каналы", systemImage: "tv").badge(store.channels.count).tag(SidebarItem.all)
                Label("Избранное", systemImage: "star.fill").badge(store.favorites.count).tag(SidebarItem.favorites)
                Label("Недавние", systemImage: "clock").tag(SidebarItem.recents)
            }
            Section("Группы") {
                ForEach(store.groups, id: \.name) { g in
                    Label(g.name, systemImage: "folder").badge(g.count).tag(SidebarItem.group(g.name))
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
    let play: (Channel) -> Void

    var body: some View {
        Group {
            if channels.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "tv.slash").font(.largeTitle).foregroundStyle(.secondary)
                    Text(store.playlists.isEmpty ? "Добавьте плейлист" : "Нет каналов").foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if gridMode {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 12) {
                        ForEach(channels) { c in
                            VStack(spacing: 6) {
                                LogoView(url: c.logo, name: c.name, size: 72)
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
        Button(store.favorites.contains(c.id) ? "Убрать из избранного" : "В избранное") { store.toggleFavorite(c) }
        Button("Копировать ссылку") {
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
            LogoView(url: channel.logo, name: channel.name, size: 38)
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
    @State private var onTop = false

    var body: some View {
        ZStack {
            Color.black
            VLCVideoContainer(controller: player)
            if store.current == nil {
                VStack(spacing: 10) {
                    Image(systemName: "play.tv").font(.system(size: 54))
                    Text("Выберите канал")
                }.foregroundStyle(.gray)
            }
            if player.isBuffering { ProgressView().controlSize(.large).tint(.white) }
            if !player.statusText.isEmpty && !player.isBuffering {
                Text(player.statusText).foregroundStyle(.white).padding(8).background(.black.opacity(0.6), in: Capsule())
            }
            VStack {
                Spacer()
                if hover || !player.isPlaying, let c = store.current { controls(c) }
            }
        }
        .onHover { hover = $0 }
        .onTapGesture(count: 2) { NSApp.keyWindow?.toggleFullScreen(nil) }
        .frame(minWidth: 420, minHeight: 260)
    }

    func controls(_ c: Channel) -> some View {
        let _ = store.epgVersion
        let (now, nextP) = store.epg.nowNext(for: c)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                LogoView(url: c.logo, name: c.name, size: 34)
                VStack(alignment: .leading) {
                    Text(c.name).font(.headline)
                    if let now {
                        Text("\(time(now.start))–\(time(now.stop))  \(now.title)").font(.caption)
                        ProgressView(value: now.progress()).frame(maxWidth: 260).tint(.white)
                    }
                    if let nextP { Text("Далее: \(time(nextP.start)) \(nextP.title)").font(.caption2).opacity(0.7) }
                }
                Spacer()
            }
            HStack(spacing: 14) {
                Button(action: prev) { Image(systemName: "backward.end.fill") }.help("Предыдущий канал (⌘↑)")
                Button { player.togglePause() } label: { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill") }
                Button(action: next) { Image(systemName: "forward.end.fill") }.help("Следующий канал (⌘↓)")
                Button { store.toggleFavorite(c) } label: {
                    Image(systemName: store.favorites.contains(c.id) ? "star.fill" : "star")
                }
                Spacer()
                if player.audioTracks.count > 1 {
                    Menu {
                        ForEach(player.audioTracks, id: \.index) { t in
                            Button((t.index == player.currentAudioTrack ? "✓ " : "") + t.name) { player.setAudioTrack(t.index) }
                        }
                    } label: { Image(systemName: "waveform") }.menuStyle(.borderlessButton).frame(width: 40)
                }
                Button { player.muted.toggle() } label: {
                    Image(systemName: player.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                }
                Slider(value: $player.volume, in: 0...150).frame(width: 110)
                Button {
                    onTop.toggle()
                    NSApp.keyWindow?.level = onTop ? .floating : .normal
                } label: { Image(systemName: onTop ? "pin.fill" : "pin") }.help("Поверх всех окон")
                Button { NSApp.keyWindow?.toggleFullScreen(nil) } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }.help("Полный экран")
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
            Text(playlist == nil ? "Новый плейлист" : "Плейлист").font(.title2.bold())
            Form {
                TextField("Название", text: $name)
                HStack {
                    TextField("Ссылка M3U / M3U8", text: $source)
                    Button("Файл…") { pickFile = true }
                }
                TextField("EPG (XMLTV), необязательно", text: $epg)
                TextField("User-Agent, необязательно", text: $ua)
                Stepper("Автообновление: каждые \(hours) ч", value: $hours, in: 1...168)
            }
            HStack {
                if let playlist {
                    Button("Удалить", role: .destructive) { store.removePlaylist(playlist); dismiss() }
                }
                Spacer()
                Button("Отмена") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Сохранить") { save() }.keyboardShortcut(.defaultAction)
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
    var body: some View {
        Form {
            Stepper("Буфер сети: \(networkCaching) мс", value: $networkCaching, in: 300...10000, step: 100)
            TextField("User-Agent по умолчанию", text: $defaultUserAgent)
            Button("Очистить кэш логотипов") { Task { await LogoCache.shared.clear() } }
        }
        .padding(20)
        .frame(width: 460)
    }
}
