import SwiftUI

/// 「夜の時計」テーマ — Android 版 Theme.kt と同じインディゴ×アンバー×ブルーグレー。
/// IBM Plex Sans JP をバンドルし全テキストに適用する。
enum NightTheme {
    static let indigo = Color(red: 0.42, green: 0.36, blue: 0.90)
    static let indigoDark = Color(red: 0.30, green: 0.25, blue: 0.78)
    static let amber = Color(red: 0.96, green: 0.70, blue: 0.28)
    static let nightBg = Color(red: 0.07, green: 0.08, blue: 0.16)
    static let nightSurface = Color(red: 0.12, green: 0.13, blue: 0.23)
    static let nightSurfaceHigh = Color(red: 0.18, green: 0.19, blue: 0.30)
    static let onNight = Color(red: 0.90, green: 0.91, blue: 0.97)

    static func font(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .light, .ultraLight, .thin: name = "IBMPlexSansJP-Light"
        case .medium: name = "IBMPlexSansJP-Medium"
        case .semibold, .bold, .heavy, .black: name = "IBMPlexSansJP-SemiBold"
        default: name = "IBMPlexSansJP-Regular"
        }
        return .custom(name, size: size)
    }
}

extension View {
    /// 全画面で Plex Sans JP を既定にする。
    func nightFont() -> some View {
        self.font(NightTheme.font(16))
    }
}

extension Color {
    /// カレンダー色 (ARGB Int) → Color
    init(argb: Int) {
        let a = Double((argb >> 24) & 0xff) / 255
        let r = Double((argb >> 16) & 0xff) / 255
        let g = Double((argb >> 8) & 0xff) / 255
        let b = Double(argb & 0xff) / 255
        self = Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
