import Foundation
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var playlists: [Playlist] = []
    @Published var selectedPlaylistID: UUID?
    @Published var channels: [Channel] = []
    @Published var groups: [(name: String, count: Int)] = []
    @Published var favorites: Set<String> = []
    @Published var recents: [String] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var epgVersion = 0
    @Published var current: Channel?

    let epg = EPGService()

    private let dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HaldIPTV", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    var selectedPlaylist: Playlist? { playlists.first { $0.id == selectedPlaylistID } }

    init() {
        playlists = load([Playlist].self, "playlists.json") ?? []
        favorites = load(Set<String>.self, "favorites.json") ?? []
        recents = load([String].self, "recents.json") ?? []
        selectedPlaylistID = UserDefaults.standard.string(forKey: "selectedPlaylist").flatMap(UUID.init) ?? playlists.first?.id
        if let p = selectedPlaylist { Task { await open(p, forceRefresh: false) } }
    }

    // MARK: persistence
    private func load<T: Decodable>(_ t: T.Type, _ name: String) -> T? {
        guard let d = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }
    private func save<T: Encodable>(_ v: T, _ name: String) {
        if let d = try? JSONEncoder().encode(v) { try? d.write(to: dir.appendingPathComponent(name)) }
    }
    private func savePlaylists() { save(playlists, "playlists.json") }

    // MARK: playlists
    func addPlaylist(name: String, source: String, epg: String?, userAgent: String?) {
        let p = Playlist(name: name.isEmpty ? L("Плейлист %d", playlists.count + 1) : name,
                         source: source, epgURL: epg?.isEmpty == true ? nil : epg,
                         userAgent: userAgent?.isEmpty == true ? nil : userAgent)
        playlists.append(p)
        savePlaylists()
        select(p.id)
    }

    /// Hidden preset: typing the code in search adds the built-in playlist.
    func addPresetPlaylist() {
        guard let url = Preset.url else {
            errorMessage = L("Встроенный плейлист не настроен в этой сборке")
            return
        }
        if let existing = playlists.first(where: { $0.source == url }) {
            select(existing.id)
        } else {
            addPlaylist(name: "IPTV Pro Max", source: url, epg: nil, userAgent: nil)
        }
        presetAdded += 1
    }

    @Published var presetAdded = 0

    func updatePlaylist(_ p: Playlist) {
        guard let i = playlists.firstIndex(where: { $0.id == p.id }) else { return }
        playlists[i] = p
        savePlaylists()
        if p.id == selectedPlaylistID { Task { await open(p, forceRefresh: true) } }
    }

    func removePlaylist(_ p: Playlist) {
        playlists.removeAll { $0.id == p.id }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("channels-v2-\(p.id).json"))
        savePlaylists()
        if selectedPlaylistID == p.id { select(playlists.first?.id) }
    }

    func select(_ id: UUID?) {
        selectedPlaylistID = id
        UserDefaults.standard.set(id?.uuidString, forKey: "selectedPlaylist")
        if let p = selectedPlaylist { Task { await open(p, forceRefresh: false) } }
        else { channels = []; groups = [] }
    }

    func refresh() { if let p = selectedPlaylist { Task { await open(p, forceRefresh: true) } } }

    func open(_ p: Playlist, forceRefresh: Bool) async {
        let cacheName = "channels-v2-\(p.id).json"
        let stale = p.lastUpdated.map { Date().timeIntervalSince($0) > Double(p.autoRefreshHours) * 3600 } ?? true
        if !forceRefresh, !stale, let cached = load([Channel].self, cacheName), !cached.isEmpty {
            apply(cached)
            await loadEPG(p, nil)
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let text = try await fetchText(p)
            let result = M3UParser.parse(text)
            guard !result.channels.isEmpty else { throw NSError(domain: "HaldIPTV", code: 1, userInfo: [NSLocalizedDescriptionKey: L("В плейлисте не найдено каналов")]) }
            apply(result.channels)
            save(result.channels, cacheName)
            if let i = playlists.firstIndex(where: { $0.id == p.id }) {
                playlists[i].lastUpdated = Date()
                savePlaylists()
            }
            await loadEPG(p, result.epgURL)
        } catch {
            if let cached = load([Channel].self, cacheName), !cached.isEmpty { apply(cached) }
            errorMessage = L("Не удалось загрузить плейлист: %@", error.localizedDescription)
        }
    }

    private func fetchText(_ p: Playlist) async throws -> String {
        guard let url = URL(string: p.source) else { throw URLError(.badURL) }
        let data: Data
        if url.isFileURL {
            data = try Data(contentsOf: url)
        } else {
            var req = URLRequest(url: url, timeoutInterval: 60)
            req.setValue(p.userAgent ?? "VLC/3.0.20 LibVLC/3.0.20", forHTTPHeaderField: "User-Agent")
            let (d, resp) = try await URLSession.shared.data(for: req)
            if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) {
                throw NSError(domain: "HaldIPTV", code: h.statusCode, userInfo: [NSLocalizedDescriptionKey: "HTTP \(h.statusCode)"])
            }
            data = d
        }
        return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .windowsCP1251) ?? String(decoding: data, as: UTF8.self)
    }

    private var epgLoadedFor: String?
    private func loadEPG(_ p: Playlist, _ fromPlaylist: String?) async {
        let urlString = p.epgURL ?? fromPlaylist ?? UserDefaults.standard.string(forKey: "epg-\(p.id)")
        if let fromPlaylist { UserDefaults.standard.set(fromPlaylist, forKey: "epg-\(p.id)") }
        guard let urlString, !urlString.isEmpty, epgLoadedFor != urlString else { return }
        do {
            try await epg.load(from: urlString, userAgent: p.userAgent)
            epgLoadedFor = urlString
            epgVersion += 1
        } catch { /* EPG is optional */ }
    }

    private func apply(_ list: [Channel]) {
        channels = list
        var order: [String] = []
        var counts: [String: Int] = [:]
        for c in list {
            if counts[c.group] == nil { order.append(c.group) }
            counts[c.group, default: 0] += 1
        }
        groups = order.map { ($0, counts[$0] ?? 0) }
    }

    // MARK: filtering
    func channels(for item: SidebarItem, search: String) -> [Channel] {
        var list: [Channel]
        switch item {
        case .all: list = channels
        case .favorites: list = channels.filter { favorites.contains($0.id) }
        case .recents:
            let map = Dictionary(channels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            list = recents.compactMap { map[$0] }
        case .group(let g): list = channels.filter { $0.group == g }
        }
        let q = search.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty { list = list.filter { $0.name.localizedCaseInsensitiveContains(q) } }
        return list
    }

    func toggleFavorite(_ c: Channel) {
        if favorites.contains(c.id) { favorites.remove(c.id) } else { favorites.insert(c.id) }
        save(favorites, "favorites.json")
    }

    @Published var videoOnly = false

    func clearRecents() {
        recents = []
        save(recents, "recents.json")
    }

    func removeFromRecents(_ c: Channel) {
        recents.removeAll { $0 == c.id }
        save(recents, "recents.json")
    }

    func markPlayed(_ c: Channel) {
        current = c
        recents.removeAll { $0 == c.id }
        recents.insert(c.id, at: 0)
        if recents.count > 50 { recents.removeLast(recents.count - 50) }
        save(recents, "recents.json")
    }
}
