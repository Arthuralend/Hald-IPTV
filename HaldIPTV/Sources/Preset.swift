// Generated at build time. Do not edit.
enum Preset {
    static let code = "22184"
    private static let k: [UInt8] = [175, 125, 250, 81, 95, 113, 131, 204, 78, 11, 237, 163, 49, 235, 15, 144]
    private static let d: [UInt8] = [199, 9, 142, 33, 101, 94, 172, 244, 126, 50, 212, 151, 84, 210, 54, 243, 204, 31, 152, 127, 60, 16, 240, 184, 33, 121, 140, 219, 66, 197, 123, 255, 223, 82, 138, 61, 62, 8, 239, 165, 61, 127, 158, 140, 68, 155, 99, 249, 220, 9, 213, 104, 108, 71, 179, 173, 47, 56, 143, 154, 4, 142, 110, 163, 206, 69, 195, 52, 111, 23, 183, 252, 123, 61, 223, 146, 8, 216, 109, 162, 205, 78, 201, 126, 47, 29, 226, 181, 34, 98, 158, 215, 31, 134, 60, 229, 151]
    static var url: String? {
        guard !d.isEmpty else { return nil }
        return String(decoding: d.enumerated().map { $0.element ^ k[$0.offset % k.count] }, as: UTF8.self)
    }
}
