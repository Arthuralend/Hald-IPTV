import Foundation

struct M3UResult {
    var channels: [Channel]
    var epgURL: String?
}

enum M3UParser {
    static let noGroup = "Без группы"

    static func parse(_ text: String) -> M3UResult {
        var channels: [Channel] = []
        var epg: String?
        var pendingAttrs: [String: String] = [:]
        var pendingName: String?
        var pendingGroup: String?
        var pendingUA: String?
        var headerAttrs: [String: String] = [:]

        let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: true)

        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.hasPrefix("#EXTM3U") {
                let a = attributes(in: line)
                headerAttrs = a
                epg = a["url-tvg"] ?? a["x-tvg-url"] ?? a["tvg-url"]
                if let e = epg, e.contains(",") { epg = e.components(separatedBy: ",").first }
            } else if line.hasPrefix("#EXTINF") {
                pendingAttrs = attributes(in: line)
                pendingName = titleAfterComma(line)
                pendingGroup = nil
            } else if line.hasPrefix("#EXTGRP:") {
                pendingGroup = String(line.dropFirst(8)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("#EXTVLCOPT:") {
                let opt = String(line.dropFirst(11))
                if opt.lowercased().hasPrefix("http-user-agent=") {
                    pendingUA = String(opt.dropFirst("http-user-agent=".count))
                }
            } else if line.hasPrefix("#") {
                continue
            } else {
                // stream URL
                let name = (pendingName?.isEmpty == false ? pendingName : pendingAttrs["tvg-name"]) ?? line
                var group = pendingAttrs["group-title"] ?? pendingGroup ?? noGroup
                if group.trimmingCharacters(in: .whitespaces).isEmpty { group = noGroup }
                let logo = pendingAttrs["tvg-logo"].flatMap { $0.isEmpty ? nil : $0 }
                let a = pendingAttrs
                let rec: String? = a["timeshift"] ?? a["tvg-rec"]
                var catchup: String? = a["catchup"] ?? a["catchup-type"] ?? headerAttrs["catchup"]
                if catchup == nil, let r = rec, r != "0", !r.isEmpty { catchup = "shift" }
                let daysText: String = a["catchup-days"] ?? rec ?? headerAttrs["catchup-days"] ?? ""
                var ch = Channel(name: name, url: line, logo: logo, group: group,
                                 tvgId: a["tvg-id"], tvgName: a["tvg-name"],
                                 userAgent: pendingUA ?? a["user-agent"])
                ch.catchup = catchup
                ch.catchupSource = a["catchup-source"] ?? headerAttrs["catchup-source"]
                ch.catchupDays = Int(daysText)
                channels.append(ch)
                pendingAttrs = [:]; pendingName = nil; pendingGroup = nil; pendingUA = nil
            }
        }
        return M3UResult(channels: channels, epgURL: epg)
    }

    /// key="value" pairs
    static func attributes(in line: String) -> [String: String] {
        var result: [String: String] = [:]
        let chars = Array(line)
        var i = 0
        while i < chars.count {
            // find '="'
            if chars[i] == "=", i + 1 < chars.count, chars[i + 1] == "\"" {
                var k = i - 1
                while k >= 0, chars[k] != " ", chars[k] != ":", chars[k] != "\t" { k -= 1 }
                let key = String(chars[(k + 1)..<i]).lowercased()
                var j = i + 2
                while j < chars.count, chars[j] != "\"" { j += 1 }
                let value = String(chars[min(i + 2, chars.count)..<min(j, chars.count)])
                if !key.isEmpty { result[key] = value }
                i = j + 1
            } else {
                i += 1
            }
        }
        return result
    }

    /// Title after the last comma that is outside of quotes
    static func titleAfterComma(_ line: String) -> String {
        var inQuotes = false
        var lastComma: String.Index?
        var idx = line.startIndex
        while idx < line.endIndex {
            let c = line[idx]
            if c == "\"" { inQuotes.toggle() }
            else if c == ",", !inQuotes { lastComma = idx }
            idx = line.index(after: idx)
        }
        guard let lc = lastComma else { return "" }
        return String(line[line.index(after: lc)...]).trimmingCharacters(in: .whitespaces)
    }
}
