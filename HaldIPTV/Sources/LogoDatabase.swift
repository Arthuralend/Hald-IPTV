import Foundation

/// Channel logo lookup in the open iptv-org database (https://github.com/iptv-org/database).
actor LogoDatabase {
    static let shared = LogoDatabase()

    private var nameIndex: [String: [String]] = [:]   // normalized name -> channel ids
    private var idIndex: [String: String] = [:]        // lowercased id -> canonical id
    private var logos: [String: String] = [:]          // channel id -> logo url
    private var loadTask: Task<Void, Never>?

    private struct DBChannel: Decodable { let id: String; let name: String; let alt_names: [String]? }
    private struct DBLogo: Decodable { let channel: String; let feed: String?; let url: String; let in_use: Bool? }

    private static let mirrors = ["https://iptv-org.github.io/api/",
                                  "https://raw.githubusercontent.com/iptv-org/api/gh-pages/"]

    private let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HaldIPTV/db", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    func ensureLoaded() async {
        if loadTask == nil { loadTask = Task { await self.load() } }
        await loadTask?.value
    }

    private func fetch(_ file: String) async -> Data? {
        let local = dir.appendingPathComponent(file)
        let attrs = try? FileManager.default.attributesOfItem(atPath: local.path)
        let fresh = (attrs?[.modificationDate] as? Date).map { Date().timeIntervalSince($0) < 7 * 86400 } ?? false
        if fresh, let d = try? Data(contentsOf: local) { return d }
        for m in Self.mirrors {
            guard let url = URL(string: m + file) else { continue }
            if let (d, r) = try? await URLSession.shared.data(from: url),
               (r as? HTTPURLResponse)?.statusCode == 200, d.count > 1000 {
                try? d.write(to: local)
                return d
            }
        }
        return try? Data(contentsOf: local) // stale cache better than nothing
    }

    private func load() async {
        guard let cd = await fetch("channels.json"), let ld = await fetch("logos.json") else { return }
        let channels = (try? JSONDecoder().decode([DBChannel].self, from: cd)) ?? []
        let logoList = (try? JSONDecoder().decode([DBLogo].self, from: ld)) ?? []
        for l in logoList where l.in_use != false {
            // prefer main-feed logo
            if l.feed == nil || logos[l.channel] == nil { logos[l.channel] = l.url }
        }
        for c in channels where logos[c.id] != nil {
            idIndex[c.id.lowercased()] = c.id
            for n in [c.name] + (c.alt_names ?? []) {
                let k = Self.normalize(n)
                guard !k.isEmpty else { continue }
                nameIndex[k, default: []].append(c.id)
                let tk = Self.translit(k)
                if tk != k { nameIndex[tk, default: []].append(c.id) }
            }
        }
    }

    func logoURL(name: String, tvgId: String?, tvgName: String?) async -> String? {
        await ensureLoaded()
        if let id = tvgId?.lowercased(), !id.isEmpty {
            let base = id.components(separatedBy: "@").first ?? id
            if let cid = idIndex[base], let u = logos[cid] { return u }
        }
        for n in [tvgName, name].compactMap({ $0 }) {
            let k = Self.normalize(n)
            guard !k.isEmpty else { continue }
            let ids = nameIndex[k] ?? nameIndex[Self.translit(k)] ?? []
            if let best = Self.pick(ids, cyrillic: Self.hasCyrillic(n)), let u = logos[best] { return u }
        }
        return nil
    }

    private static func pick(_ ids: [String], cyrillic: Bool) -> String? {
        guard !ids.isEmpty else { return nil }
        let preferred = cyrillic ? [".ru", ".am", ".by", ".kz", ".ua"] : [".am", ".ru", ".us", ".uk"]
        for suffix in preferred {
            if let id = ids.first(where: { $0.lowercased().hasSuffix(suffix) && !$0.contains("International") }) { return id }
        }
        return ids.first
    }

    static func hasCyrillic(_ s: String) -> Bool {
        s.unicodeScalars.contains { (0x0400...0x04FF).contains($0.value) }
    }

    static func normalize(_ s: String) -> String {
        var t = s.lowercased()
        t = t.replacingOccurrences(of: #"\[[^\]]*\]|\([^)]*\)"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"\b(hd|fhd|uhd|4k|sd|hevc|orig|original|int|international|\+\d+|-\d+)\b"#,
                                   with: " ", options: .regularExpression)
        return t.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }

    private static let tr: [Character: String] = [
        "а":"a","б":"b","в":"v","г":"g","д":"d","е":"e","ё":"e","ж":"zh","з":"z","и":"i","й":"y","к":"k",
        "л":"l","м":"m","н":"n","о":"o","п":"p","р":"r","с":"s","т":"t","у":"u","ф":"f","х":"kh","ц":"ts",
        "ч":"ch","ш":"sh","щ":"shch","ъ":"","ы":"y","ь":"","э":"e","ю":"yu","я":"ya"]

    static func translit(_ s: String) -> String { s.map { tr[$0] ?? String($0) }.joined() }
}
