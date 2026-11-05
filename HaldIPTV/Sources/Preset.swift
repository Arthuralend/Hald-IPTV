// Generated at build time. Do not edit.
enum Preset {
    static let code = "22184"
    private static let k: [UInt8] = [220, 105, 157, 218, 41, 16, 130, 211, 36, 23, 159, 197, 121, 77, 96, 186]
    private static let d: [UInt8] = []
    static var url: String? {
        guard !d.isEmpty else { return nil }
        return String(decoding: d.enumerated().map { $0.element ^ k[$0.offset % k.count] }, as: UTF8.self)
    }
}
