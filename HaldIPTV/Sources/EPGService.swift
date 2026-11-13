import Foundation

/// TV guide (XMLTV, .xml or .xml.gz). Channels are matched by tvg-id or by normalized name,
/// the same normalization as the logo index ("Первый канал HD" == "Первый канал").
final class EPGService {
    /// Default sources used when the playlist does not name one (the iLook TV guide first).
    static let defaultSources = [
        "http://epg.it999.ru/epg2.xml.gz",
        "https://iptvx.one/epg/epg.xml.gz",
    ]

    /// channel key -> programmes sorted by start
    private(set) var programmes: [String: [Programme]] = [:]
    /// normalized display-name -> xmltv channel id
    private(set) var nameIndex: [String: String] = [:]
    /// channel icons from the guide (normalized name -> url), used as logos
    private static var icons: [String: String] = [:]
    private static let lock = NSLock()

    static func icon(forName name: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        for k in LogoDatabase.keys(name) { if let u = icons[k] { return u } }
        return nil
    }

    private let cacheDir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HaldIPTV/epg", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    /// Loads the first source that works.
    func load(from sources: [String], userAgent: String?) async throws {
        var lastError: Error = URLError(.badURL)
        for s in sources {
            do { try await load(from: s, userAgent: userAgent); return } catch { lastError = error }
        }
        throw lastError
    }

    func load(from urlString: String, userAgent: String?) async throws {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        let cache = cacheDir.appendingPathComponent(String(urlString.hashValue & 0x7fffffff) + ".xml")
        var data: Data
        let attrs = try? FileManager.default.attributesOfItem(atPath: cache.path)
        if let m = attrs?[.modificationDate] as? Date, Date().timeIntervalSince(m) < 8 * 3600,
           let d = try? Data(contentsOf: cache) {
            data = d
        } else {
            var req = URLRequest(url: url, timeoutInterval: 180)
            req.setValue(userAgent?.isEmpty == false ? userAgent! : "Mozilla/5.0", forHTTPHeaderField: "User-Agent")
            data = try await URLSession.shared.fetch(req).0
            if data.count > 2, data[0] == 0x1f, data[1] == 0x8b { data = try Self.gunzip(data) }
            guard data.count > 1000 else { throw URLError(.cannotParseResponse) }
            try? data.write(to: cache)
        }
        let result = await Task.detached(priority: .utility) { XMLTVParser().parse(data) }.value
        guard !result.programmes.isEmpty else { throw URLError(.cannotParseResponse) }
        programmes = result.programmes
        nameIndex = result.names
        Self.lock.lock(); Self.icons = result.icons; Self.lock.unlock()
    }

    func schedule(for channel: Channel) -> [Programme] {
        if let id = channel.tvgId, !id.isEmpty, let p = programmes[id] { return p }
        for n in [channel.tvgName, channel.name].compactMap({ $0 }) {
            for k in LogoDatabase.keys(n) {
                if let id = nameIndex[k], let p = programmes[id] { return p }
            }
        }
        return []
    }

    func nowNext(for channel: Channel, at now: Date = Date()) -> (Programme?, Programme?) {
        let list = schedule(for: channel)
        guard let i = list.firstIndex(where: { $0.start <= now && $0.stop > now }) else {
            return (nil, list.first(where: { $0.start > now }))
        }
        return (list[i], i + 1 < list.count ? list[i + 1] : nil)
    }

    static func gunzip(_ data: Data) throws -> Data {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gz")
        try data.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/gunzip")
        p.arguments = ["-c", tmp.path]
        let pipe = Pipe()
        p.standardOutput = pipe
        try p.run()
        let out = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return out
    }
}

private final class XMLTVParser: NSObject, XMLParserDelegate {
    struct Result { var programmes: [String: [Programme]]; var names: [String: String]; var icons: [String: String] }

    private var programmes: [String: [Programme]] = [:]
    private var names: [String: String] = [:]
    private var icons: [String: String] = [:]
    private var channelIcon: [String: String] = [:]
    private var channelNames: [String: [String]] = [:]
    private var currentChannelId: String?
    private var progChannel: String?
    private var progStart: Date?
    private var progStop: Date?
    private var title = ""
    private var desc = ""
    private var text = ""
    private let windowStart = Date().addingTimeInterval(-7 * 86400)   // archive up to a week back
    private let windowEnd = Date().addingTimeInterval(2 * 86400)

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMddHHmmss Z"
        return f
    }()
    private static let formatterNoTZ: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMddHHmmss"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    static func date(_ s: String?) -> Date? {
        guard let s = s?.trimmingCharacters(in: .whitespaces) else { return nil }
        return formatter.date(from: s) ?? formatterNoTZ.date(from: String(s.prefix(14)))
    }

    func parse(_ data: Data) -> Result {
        let p = XMLParser(data: data)
        p.delegate = self
        p.parse()
        for k in programmes.keys { programmes[k]?.sort { $0.start < $1.start } }
        for (id, ns) in channelNames {
            for n in ns {
                for k in LogoDatabase.keys(n) {
                    if names[k] == nil { names[k] = id }
                    if let ic = channelIcon[id], icons[k] == nil { icons[k] = ic }
                }
            }
        }
        return Result(programmes: programmes, names: names, icons: icons)
    }

    func parser(_ parser: XMLParser, didStartElement el: String, namespaceURI: String?,
                qualifiedName: String?, attributes a: [String: String] = [:]) {
        text = ""
        switch el {
        case "channel": currentChannelId = a["id"]
        case "icon":
            if let id = currentChannelId, let src = a["src"], !src.isEmpty, channelIcon[id] == nil { channelIcon[id] = src }
        case "programme":
            progChannel = a["channel"]; progStart = Self.date(a["start"]); progStop = Self.date(a["stop"])
            title = ""; desc = ""
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement el: String, namespaceURI: String?, qualifiedName: String?) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch el {
        case "display-name":
            if let id = currentChannelId, !t.isEmpty { channelNames[id, default: []].append(t) }
        case "channel": currentChannelId = nil
        case "title": if progChannel != nil, title.isEmpty { title = t }
        case "desc": if progChannel != nil, desc.isEmpty { desc = t }
        case "programme":
            if let ch = progChannel, let s = progStart, let e = progStop, e > windowStart, s < windowEnd {
                programmes[ch, default: []].append(Programme(start: s, stop: e, title: title, desc: desc.isEmpty ? nil : desc))
            }
            progChannel = nil
        default: break
        }
        text = ""
    }
}
