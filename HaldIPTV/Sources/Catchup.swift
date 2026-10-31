import Foundation

/// Builds provider archive (catch-up) URLs in the formats used by TiviMate / IPTV Pro / Kodi.
enum Catchup {
    static func url(for c: Channel, start: Date, now: Date = Date()) -> String? {
        guard c.hasArchive else { return nil }
        let type = (c.catchup ?? "").lowercased()
        let s = Int(start.timeIntervalSince1970)
        let n = Int(now.timeIntervalSince1970)
        let duration = max(60, n - s)

        switch type {
        case "flussonic", "flussonic-hls", "flussonic-ts", "fs":
            return flussonic(c.url, start: s, duration: duration)
        case "shift", "timeshift", "archive":
            if let src = c.catchupSource, !src.isEmpty { return fill(src, base: c.url, start: start, now: now) }
            return c.url + (c.url.contains("?") ? "&" : "?") + "utc=\(s)&lutc=\(n)"
        case "append":
            guard let src = c.catchupSource else { return c.url + (c.url.contains("?") ? "&" : "?") + "utc=\(s)&lutc=\(n)" }
            return c.url + fill(src, base: c.url, start: start, now: now)
        default: // "default", "1", "true", custom templates
            if let src = c.catchupSource, !src.isEmpty {
                let filled = fill(src, base: c.url, start: start, now: now)
                return filled.hasPrefix("http") ? filled : c.url + filled
            }
            if c.url.contains("/index.m3u8") || c.url.contains("/mpegts") || c.url.contains("/video.m3u8") {
                return flussonic(c.url, start: s, duration: duration)
            }
            return c.url + (c.url.contains("?") ? "&" : "?") + "utc=\(s)&lutc=\(n)"
        }
    }

    static func flussonic(_ url: String, start: Int, duration: Int) -> String {
        for name in ["index", "video", "mono", "playlist"] where url.contains("/\(name).m3u8") {
            return url.replacingOccurrences(of: "/\(name).m3u8", with: "/\(name)-\(start)-\(duration).m3u8")
        }
        if url.contains("/mpegts") {
            return url.replacingOccurrences(of: "/mpegts", with: "/timeshift_abs-\(start).ts")
        }
        // generic: insert before query
        let parts = url.split(separator: "?", maxSplits: 1).map(String.init)
        let base = parts[0]
        let q = parts.count > 1 ? "?" + parts[1] : ""
        if base.hasSuffix(".m3u8") {
            return base.replacingOccurrences(of: ".m3u8", with: "-\(start)-\(duration).m3u8") + q
        }
        return base + "/timeshift_abs-\(start).ts" + q
    }

    static func fill(_ template: String, base: String, start: Date, now: Date) -> String {
        let s = Int(start.timeIntervalSince1970)
        let n = Int(now.timeIntervalSince1970)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let d = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: start)
        func p2(_ v: Int?) -> String { String(format: "%02d", v ?? 0) }
        let map: [String: String] = [
            "utc": "\(s)", "start": "\(s)", "lutc": "\(n)", "now": "\(n)", "timestamp": "\(n)",
            "utcend": "\(n)", "end": "\(n)", "duration": "\(n - s)", "offset": "\(n - s)",
            "Y": "\(d.year ?? 0)", "m": p2(d.month), "d": p2(d.day), "H": p2(d.hour), "M": p2(d.minute), "S": p2(d.second),
        ]
        var out = template
        for (k, v) in map {
            out = out.replacingOccurrences(of: "${\(k)}", with: v)
            out = out.replacingOccurrences(of: "{\(k)}", with: v)
        }
        // {utc:Y-m-d-H-M-S} style
        if let r = out.range(of: #"\{utc:([^}]*)\}"#, options: .regularExpression) {
            var fmt = String(out[r]).dropFirst(5).dropLast()
            for (k, v) in [("Y", "\(d.year ?? 0)"), ("m", p2(d.month)), ("d", p2(d.day)), ("H", p2(d.hour)), ("M", p2(d.minute)), ("S", p2(d.second))] {
                fmt = Substring(fmt.replacingOccurrences(of: k, with: v))
            }
            out.replaceSubrange(r, with: String(fmt))
        }
        _ = base
        return out
    }
}
