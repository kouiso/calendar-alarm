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

/// 元アプリのテーマ10種。Android Palettes.kt と同じ primary カラーでアクセントを決める。
/// iOS は .tint 1色主導なので、パレットのライト側 primary をアクセントに使う。
enum AppPalette: String, CaseIterable {
    case indigo, amber, forest, ocean, rose, violet, mono, sky, orange, sakura

    var label: String {
        switch self {
        case .indigo: return "インディゴ"
        case .amber: return "琥珀"
        case .forest: return "森"
        case .ocean: return "海"
        case .rose: return "薔薇"
        case .violet: return "紫"
        case .mono: return "墨"
        case .sky: return "空"
        case .orange: return "橙"
        case .sakura: return "桜"
        }
    }

    /// Android 版の light primary (ARGMX hex) と同値。
    var accent: Color {
        switch self {
        case .indigo: return Color(hex: 0xFF4A46D4)
        case .amber: return Color(hex: 0xFF8B5000)
        case .forest: return Color(hex: 0xFF386A20)
        case .ocean: return Color(hex: 0xFF006496)
        case .rose: return Color(hex: 0xFFA61B5E)
        case .violet: return Color(hex: 0xFF7B4FA6)
        case .mono: return Color(hex: 0xFF424242)
        case .sky: return Color(hex: 0xFF0061A4)
        case .orange: return Color(hex: 0xFF964900)
        case .sakura: return Color(hex: 0xFF984061)
        }
    }

    /// 設定の themeId から解決 ("default" や未知値はインディゴ)。
    static func byId(_ id: String) -> AppPalette {
        AppPalette(rawValue: id) ?? .indigo
    }
}

extension Color {
    /// 0xAARRGGBB または 0xRRGGBB (上位8bitを無視) から Color を作る。
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xff) / 255
        let g = Double((hex >> 8) & 0xff) / 255
        let b = Double(hex & 0xff) / 255
        let a = hex > 0xFFFFFF ? Double((hex >> 24) & 0xff) / 255 : 1.0
        self = Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    }

    /// カレンダー色 (ARGB Int) → Color
    init(argb: Int) {
        let a = Double((argb >> 24) & 0xff) / 255
        let r = Double((argb >> 16) & 0xff) / 255
        let g = Double((argb >> 8) & 0xff) / 255
        let b = Double(argb & 0xff) / 255
        self = Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
