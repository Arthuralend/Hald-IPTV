import AppKit
import CryptoKit
import SwiftUI

actor LogoCache {
    static let shared = LogoCache()
    private let memory = NSCache<NSString, NSImage>()
    private var inFlight: [String: Task<NSImage?, Never>] = [:]
    private let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HaldIPTV/logos", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    init() { memory.countLimit = 2000 }

    private func key(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func image(for urlString: String) async -> NSImage? {
        if let img = memory.object(forKey: urlString as NSString) { return img }
        let file = dir.appendingPathComponent(key(urlString))
        if let img = NSImage(contentsOf: file) {
            memory.setObject(img, forKey: urlString as NSString)
            return img
        }
        if let t = inFlight[urlString] { return await t.value }
        let t = Task<NSImage?, Never> {
            guard let url = URL(string: urlString) else { return nil }
            var req = URLRequest(url: url, timeoutInterval: 20)
            req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
            guard let (data, _) = try? await URLSession.shared.fetch(req),
                  let img = NSImage(data: data) else { return nil }
            try? data.write(to: file)
            return img
        }
        inFlight[urlString] = t
        let img = await t.value
        inFlight[urlString] = nil
        if let img { memory.setObject(img, forKey: urlString as NSString) }
        return img
    }

    func clear() {
        memory.removeAllObjects()
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
}

struct LogoView: View {
    let channel: Channel
    var size: CGFloat = 36
    @AppStorage("useLogoDB") private var useLogoDB = true
    @AppStorage("preferLogoDB") private var preferLogoDB = false
    @EnvironmentObject private var store: AppStore
    private var epgVersion: Int { store.epgVersion }
    private var name: String { channel.name }
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
            } else {
                RoundedRectangle(cornerRadius: size * 0.2)
                    .fill(LinearGradient(colors: [color.opacity(0.85), color.opacity(0.55)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay(Text(initials).font(.system(size: size * 0.36, weight: .bold)).foregroundColor(.white))
            }
        }
        .frame(width: size, height: size)
        .reload(on: channel.id + "\(useLogoDB)\(preferLogoDB)\(epgVersion)") {
            image = nil
            let own = channel.logo.flatMap { $0.isEmpty ? nil : $0 }
            if !preferLogoDB, let own { image = await LogoCache.shared.image(for: own) }
            if image == nil, let ic = EPGService.icon(forName: channel.tvgName ?? channel.name) {
                image = await LogoCache.shared.image(for: ic)
            }
            if image == nil, useLogoDB || preferLogoDB,
               let db = await LogoDatabase.shared.logoURL(name: channel.name, tvgId: channel.tvgId, tvgName: channel.tvgName) {
                image = await LogoCache.shared.image(for: db)
            }
            if image == nil, preferLogoDB, let own { image = await LogoCache.shared.image(for: own) }
        }
    }

    private var initials: String {
        let words = name.split(separator: " ").prefix(2)
        return words.compactMap { $0.first.map(String.init) }.joined().uppercased()
    }

    private var color: Color {
        let palette: [Color] = [Theme.orange, Theme.orangeDeep, Color(red: 0.36, green: 0.62, blue: 0.75), Color(red: 0.42, green: 0.43, blue: 0.46)]
        let h = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xffff }
        return palette[h % palette.count]
    }
}
