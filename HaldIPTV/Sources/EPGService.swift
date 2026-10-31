import Foundation

/// XMLTV loader (supports .xml and .xml.gz)
final class EPGService {
    /// channel key -> programmes sorted by start
    private(set) var programmes: [String: [Programme]] = [:]
    /// lowercased display-name -> channel id
    private(set) var nameIndex: [String: String] = [:]

    func load(from urlString: String, userAgent: String?) async throws {
        guard let url = URL(string: urlString) else { return }
        var req = URLRequest(url: url, timeoutInterval: 120)
        if let ua = userAgent, !ua.isEmpty { req.setValue(ua, forHTTPHeaderField: "User-Agent") }
        var (data, _) = try await URLSession.shared.data(for: req)
        if data.count > 2, data[0] == 0x1f, data[1] == 0x8b {
            data = try Self.gunzip(data)
        }
        let parser = XMLTVParser()
        let result = parser.parse(data)
        programmes = result.0
        nameIndex = result.1
    }

    func schedule(for channel: Channel) -> [Programme] {
        if let id = channel.tvgId, !id.isEmpty, let p = programmes[id] { return p }
        for key in [channel.tvgName, channel.name].compactMap({ $0?.lowercased() }) {
            if let id = nameIndex[key], let p = programmes[id] { return p }
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
    private var programmes: [String: [Programme]] = [:]
    private var names: [String: String] = [:]
    private var currentChannelId: String?
    private var progChannel: String?
    private var progStart: Date?
    private var progStop: Date?
    private var title = ""
    private var desc = ""
    private var text = ""
    private let windowStart = Date().addingTimeInterval(-6 * 3600)
    private let windowEnd = Date().addingTimeInterval(36 * 3600)

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

    func parse(_ data: Data) -> ([String: [Programme]], [String: String]) {
        let p = XMLParser(data: data)
        p.delegate = self
        p.parse()
        for k in programmes.keys { programmes[k]?.sort { $0.start < $1.start } }
        return (programmes, names)
    }

    func parser(_ parser: XMLParser, didStartElement el: String, namespaceURI: String?,
                qualifiedName: String?, attributes a: [String: String] = [:]) {
        text = ""
        switch el {
        case "channel": currentChannelId = a["id"]
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
            if let id = currentChannelId, !t.isEmpty { names[t.lowercased()] = id }
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
