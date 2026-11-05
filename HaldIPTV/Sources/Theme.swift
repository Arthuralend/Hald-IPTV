import SwiftUI

/// Colors taken from the app icon: graphite tile, orange TV cabinet, pale-blue screen.
enum Theme {
    static let orange      = Color(red: 0.949, green: 0.502, blue: 0.039)   // #F2800A
    static let orangeDeep  = Color(red: 0.851, green: 0.384, blue: 0.000)   // #D96200
    static let screen      = Color(red: 0.741, green: 0.910, blue: 0.973)   // #BDE8F8
    static let background  = Color(red: 0.118, green: 0.122, blue: 0.129)   // #1E1F21
    static let sidebar     = Color(red: 0.145, green: 0.149, blue: 0.157)   // #252628
    static let panel       = Color(red: 0.169, green: 0.173, blue: 0.180)   // #2B2C2E
    static let card        = Color(red: 0.208, green: 0.212, blue: 0.220)   // #353638
    static let line        = Color.white.opacity(0.08)
    static let textDim     = Color.white.opacity(0.55)

    static let orangeGradient = LinearGradient(colors: [orange, orangeDeep], startPoint: .top, endPoint: .bottom)
}
