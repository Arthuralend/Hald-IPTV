import Foundation

struct Channel: Identifiable, Hashable, Codable {
    var id: String { url + "|" + name }
    var name: String
    var url: String
    var logo: String?
    var group: String
    var tvgId: String?
    var tvgName: String?
    var userAgent: String?
}

struct Playlist: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    /// http(s) URL or file:// URL
    var source: String
    var epgURL: String?
    var userAgent: String?
    var lastUpdated: Date?
    var autoRefreshHours: Int = 24

    var isRemote: Bool { source.hasPrefix("http") }
}

struct Programme: Codable, Hashable {
    var start: Date
    var stop: Date
    var title: String
    var desc: String?

    func progress(at now: Date = Date()) -> Double {
        let total = stop.timeIntervalSince(start)
        guard total > 0 else { return 0 }
        return min(1, max(0, now.timeIntervalSince(start) / total))
    }
}

enum SidebarItem: Hashable {
    case all
    case favorites
    case recents
    case group(String)

    var title: String {
        switch self {
        case .all: return "Все каналы"
        case .favorites: return "Избранное"
        case .recents: return "Недавние"
        case .group(let g): return g
        }
    }
}
