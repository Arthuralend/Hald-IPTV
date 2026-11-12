import Foundation

/// Channel logos from the open iptv-org database, bundled as logos.tsv
/// (built by tools/gen_logo_index.py — normalization here must match it; same as the Android TV app).
actor LogoDatabase {
    static let shared = LogoDatabase()

    private var map: [String: [(id: String, url: String)]]?

    private func index() -> [String: [(id: String, url: String)]] {
        if let map { return map }
        var m: [String: [(id: String, url: String)]] = [:]
        if let url = Bundle.main.url(forResource: "logos", withExtension: "tsv"),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            m.reserveCapacity(60000)
            for line in text.split(separator: "\n") {
                let parts = line.split(separator: "\t")
                guard parts.count >= 2 else { continue }
                m[String(parts[0])] = parts.dropFirst().compactMap { p in
                    guard let sp = p.firstIndex(of: " ") else { return nil }
                    return (String(p[..<sp]), String(p[p.index(after: sp)...]))
                }
            }
        }
        map = m
        return m
    }

    func ensureLoaded() async { _ = index() }

    private static let quality: Set<String> = ["uhd", "fhd", "hd", "4k", "sd", "hevc", "orig", "original", "50", "60", "hq"]
    private static let generic: Set<String> = ["телеканал", "канал", "tv", "тв", "channel"]
    private static let tr: [Character: String] = [
        "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e", "ж": "zh", "з": "z", "и": "i", "й": "y",
        "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u", "ф": "f",
        "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch", "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
        "і": "i", "ї": "yi", "є": "ye", "ґ": "g",
    ]

    private static func isCountry(_ w: String) -> Bool {
        w.count == 2 && w.unicodeScalars.allSatisfy { $0.value >= 97 && $0.value <= 122 }
    }

    private static func rawWords(_ s: String) -> [String] {
        var t = s.lowercased().replacingOccurrences(of: "ё", with: "е")
        t = t.replacingOccurrences(of: #"\[[^\]]*\]|\([^)]*\)"#, with: " ", options: .regularExpression)
        t = t.replacingOccurrences(of: #"\s\+\s*\d+.*$"#, with: " ", options: .regularExpression)
        return t.components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_+!")).inverted)
            .filter { !$0.isEmpty }
            .map { $0 == "+" ? "plus" : $0 }
    }

    private static func words(_ s: String) -> [String] {
        var w = rawWords(s)
        while w.count > 1, let last = w.last, quality.contains(last) || isCountry(last) { w.removeLast() }
        if w.count > 1, w[0] == "vf" { w.removeFirst() }
        return w
    }

    private static func join(_ w: [String]) -> String {
        String(w.joined().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == "_" })
    }

    private static func translit(_ s: String) -> String { s.map { tr[$0] ?? String($0) }.joined() }

    private static func keys(_ name: String) -> [String] {
        let w = words(name)
        var out = [join(w)]
        let g = w.filter { !generic.contains($0) }
        if !g.isEmpty && g != w { out.append(join(g)) }
        var seen = Set<String>()
        return out.filter { !$0.isEmpty }.flatMap { [$0, translit($0)] }.filter { seen.insert($0).inserted }
    }

    private static func preferred(_ name: String) -> [String] {
        let raw = rawWords(name)
        var cc: [String] = []
        if raw.count > 1, let last = raw.last, isCountry(last), !quality.contains(last) { cc.append(last) }
        let cyr = name.unicodeScalars.contains { $0.value >= 0x400 && $0.value <= 0x4FF }
        let base = cyr ? ["ru", "am", "by", "kz", "ua"] : ["am", "us", "uk", "ru"]
        var seen = Set<String>()
        return (cc + base).filter { seen.insert($0).inserted }
    }

    func logoURL(name: String, tvgId: String?, tvgName: String?) async -> String? {
        let m = index()
        for n in [tvgName, name].compactMap({ $0 }) where !n.isEmpty {
            for k in Self.keys(n) {
                guard let cands = m[k], !cands.isEmpty else { continue }
                for cc in Self.preferred(n) {
                    if let c = cands.first(where: { $0.id.lowercased().hasSuffix("." + cc) }) { return c.url }
                }
                return cands[0].url
            }
        }
        return nil
    }
}
