import SwiftUI

/// 「夜の時計」テーマ — Night UI デザインスペック準拠 (濃紺ベース + 琥珀アクセント)。
/// IBM Plex Sans JP をバンドルし全テキストに適用、時刻数字は Outfit を使う。
/// 各カラーはライト/ダークで動的解決する (外観は端末設定に連動)。
enum NightTheme {
    static let indigo = Color(red: 0.42, green: 0.36, blue: 0.90)
    static let indigoDark = Color(red: 0.30, green: 0.25, blue: 0.78)
    static let amber = adaptive(dark: 0xFFB870, light: 0x8B5000)
    static let nightBg = adaptive(dark: 0x0A0C11, light: 0xF4F5F8)
    static let nightSurface = adaptive(dark: 0x12151C, light: 0xFFFFFF)
    static let nightSurfaceHigh = adaptive(dark: 0x161A23, light: 0xFFFFFF)
    static let onNight = adaptive(dark: 0xECEEF3, light: 0x14171F)
    static let muted = adaptive(dark: 0x9AA3B2, light: 0x5B6372)
    static let divider = adaptive(dark: 0x1F2430, light: 0xE6E8EE)
    static let border = adaptive(dark: 0x262C38, light: 0xD7D8E3)

    /// ライト/ダークで色を切り替える (Night UI スペックのペア値)。
    static func adaptive(dark: UInt32, light: UInt32) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(Color(hex: dark)) : UIColor(Color(hex: light))
        })
    }

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

    /// 時刻・数字専用書体: Outfit (Thin系幾何学サンセリフ、OFL)。PostScript名で参照。
    static func numFont(_ size: CGFloat, weight: Font.Weight = .light) -> Font {
        let name: String
        switch weight {
        case .ultraLight, .thin: name = "Outfit-ExtraLight"
        case .light: name = "Outfit-Light"
        case .medium: name = "Outfit-Medium"
        case .semibold, .bold, .heavy, .black: name = "Outfit-SemiBold"
        default: name = "Outfit-Regular"
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

    /// Android 版 Palettes.kt の primary と同値。ダーク時は dark primary に切替。
    var accent: Color {
        switch self {
        case .indigo: return NightTheme.adaptive(dark: 0xFFBDBDFF, light: 0xFF4A46D4)
        case .amber: return NightTheme.adaptive(dark: 0xFFFFB870, light: 0xFF8B5000)
        case .forest: return NightTheme.adaptive(dark: 0xFFA4D396, light: 0xFF386A20)
        case .ocean: return NightTheme.adaptive(dark: 0xFF92CCFF, light: 0xFF006496)
        case .rose: return NightTheme.adaptive(dark: 0xFFFFB1C4, light: 0xFFA61B5E)
        case .violet: return NightTheme.adaptive(dark: 0xFFDEBDFE, light: 0xFF7B4FA6)
        case .mono: return NightTheme.adaptive(dark: 0xFFC2C2C2, light: 0xFF424242)
        case .sky: return NightTheme.adaptive(dark: 0xFF9FCAFF, light: 0xFF0061A4)
        case .orange: return NightTheme.adaptive(dark: 0xFFFFB877, light: 0xFF964900)
        case .sakura: return NightTheme.adaptive(dark: 0xFFFFB1C2, light: 0xFF984061)
        }
    }

    /// 設定の themeId から解決 ("default" や未知値は琥珀 — Night UI の既定)。
    static func byId(_ id: String) -> AppPalette {
        AppPalette(rawValue: id) ?? .amber
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
