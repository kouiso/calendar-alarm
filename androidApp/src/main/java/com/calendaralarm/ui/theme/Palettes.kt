package com.calendaralarm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * カラーテーマ10種 (元アプリのテーマ選択に相当)。
 * ニュートラル/サーフェス階層は全テーマ共通で、アクセント系
 * (primary/secondary/tertiary とその container) だけをパレットで差し替える。
 * こうすると選択チップ・ボタン・FAB 等の「色が付く部品」が一括で変わる。
 */
class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

enum class AppPalette(
    val id: String,
    val label: String,
    val light: AccentColors,
    val dark: AccentColors,
) {
    INDIGO(
        "indigo", "インディゴ",
        light = AccentColors(
            primary = Color(0xFF4A46D4), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE3E0FF), onPrimaryContainer = Color(0xFF120D69),
            secondary = Color(0xFF585A92), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF1A1B4B),
            tertiary = Color(0xFF006A63), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFF9EF2E7), onTertiaryContainer = Color(0xFF00201D),
        ),
        dark = AccentColors(
            primary = Color(0xFFBDBDFF), onPrimary = Color(0xFF26247A),
            primaryContainer = Color(0xFF3B3794), onPrimaryContainer = Color(0xFFE3E0FF),
            secondary = Color(0xFFC4C3EA), onSecondary = Color(0xFF2D2E55),
            secondaryContainer = Color(0xFF444462), onSecondaryContainer = Color(0xFFE1E0F9),
            tertiary = Color(0xFF86D2C7), onTertiary = Color(0xFF003730),
            tertiaryContainer = Color(0xFF00504A), onTertiaryContainer = Color(0xFF9EF2E7),
        ),
    ),
    AMBER(
        "amber", "琥珀",
        light = AccentColors(
            primary = Color(0xFF8B5000), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDCBE), onPrimaryContainer = Color(0xFF2C1600),
            secondary = Color(0xFF745A42), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDCBE), onSecondaryContainer = Color(0xFF2C1600),
            tertiary = Color(0xFF59636B), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFDCE3ED), onTertiaryContainer = Color(0xFF161F26),
        ),
        dark = AccentColors(
            primary = Color(0xFFFFB870), onPrimary = Color(0xFF4A2800),
            primaryContainer = Color(0xFF6A3C00), onPrimaryContainer = Color(0xFFFFDCBE),
            secondary = Color(0xFFE3C0A0), onSecondary = Color(0xFF422C18),
            secondaryContainer = Color(0xFF5B432F), onSecondaryContainer = Color(0xFFFFDCBE),
            tertiary = Color(0xFFC0C9D4), onTertiary = Color(0xFF2B333B),
            tertiaryContainer = Color(0xFF414951), onTertiaryContainer = Color(0xFFDCE3ED),
        ),
    ),
    FOREST(
        "forest", "森",
        light = AccentColors(
            primary = Color(0xFF386A20), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFC0EFB0), onPrimaryContainer = Color(0xFF042100),
            secondary = Color(0xFF55624C), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDAE7C9), onSecondaryContainer = Color(0xFF131F0D),
            tertiary = Color(0xFF386666), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFBCEBEA), onTertiaryContainer = Color(0xFF002020),
        ),
        dark = AccentColors(
            primary = Color(0xFFA4D396), onPrimary = Color(0xFF0E3900),
            primaryContainer = Color(0xFF205106), onPrimaryContainer = Color(0xFFC0EFB0),
            secondary = Color(0xFFBECBAB), onSecondary = Color(0xFF283424),
            secondaryContainer = Color(0xFF3E4B35), onSecondaryContainer = Color(0xFFDAE7C9),
            tertiary = Color(0xFFA0CFCF), onTertiary = Color(0xFF003737),
            tertiaryContainer = Color(0xFF1E4E4E), onTertiaryContainer = Color(0xFFBCEBEA),
        ),
    ),
    OCEAN(
        "ocean", "海",
        light = AccentColors(
            primary = Color(0xFF006496), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFCDE5FF), onPrimaryContainer = Color(0xFF001E31),
            secondary = Color(0xFF51606F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD5E4F6), onSecondaryContainer = Color(0xFF0D1D28),
            tertiary = Color(0xFF68587A), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFEFDFFF), onTertiaryContainer = Color(0xFF231533),
        ),
        dark = AccentColors(
            primary = Color(0xFF92CCFF), onPrimary = Color(0xFF003350),
            primaryContainer = Color(0xFF004B76), onPrimaryContainer = Color(0xFFCDE5FF),
            secondary = Color(0xFFB9C8DA), onSecondary = Color(0xFF223242),
            secondaryContainer = Color(0xFF384857), onSecondaryContainer = Color(0xFFD5E4F6),
            tertiary = Color(0xFFD3BFE6), onTertiary = Color(0xFF372A47),
            tertiaryContainer = Color(0xFF4E4160), onTertiaryContainer = Color(0xFFEFDFFF),
        ),
    ),
    ROSE(
        "rose", "薔薇",
        light = AccentColors(
            primary = Color(0xFFA61B5E), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3E001B),
            secondary = Color(0xFF74565E), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFD9E2), onSecondaryContainer = Color(0xFF2B151B),
            tertiary = Color(0xFF7C5635), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDCC0), onTertiaryContainer = Color(0xFF2E1500),
        ),
        dark = AccentColors(
            primary = Color(0xFFFFB1C4), onPrimary = Color(0xFF66013A),
            primaryContainer = Color(0xFF8E0048), onPrimaryContainer = Color(0xFFFFD9E2),
            secondary = Color(0xFFE3BDC4), onSecondary = Color(0xFF432930),
            secondaryContainer = Color(0xFF592C33), onSecondaryContainer = Color(0xFFFFD9E2),
            tertiary = Color(0xFFEFC08C), onTertiary = Color(0xFF452B0F),
            tertiaryContainer = Color(0xFF5F4224), onTertiaryContainer = Color(0xFFFFDCC0),
        ),
    ),
    VIOLET(
        "violet", "紫",
        light = AccentColors(
            primary = Color(0xFF7B4FA6), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF1DAFF), onPrimaryContainer = Color(0xFF2C0050),
            secondary = Color(0xFF665A6F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEDDDF7), onSecondaryContainer = Color(0xFF21182A),
            tertiary = Color(0xFF805158), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD9DD), onTertiaryContainer = Color(0xFF321011),
        ),
        dark = AccentColors(
            primary = Color(0xFFDEBDFE), onPrimary = Color(0xFF431F66),
            primaryContainer = Color(0xFF5B3786), onPrimaryContainer = Color(0xFFF1DAFF),
            secondary = Color(0xFFD0C1DA), onSecondary = Color(0xFF372E41),
            secondaryContainer = Color(0xFF4E4458), onSecondaryContainer = Color(0xFFEDDDF7),
            tertiary = Color(0xFFF4B7BF), onTertiary = Color(0xFF4A252A),
            tertiaryContainer = Color(0xFF633B40), onTertiaryContainer = Color(0xFFFFD9DD),
        ),
    ),
    MONO(
        "mono", "墨",
        light = AccentColors(
            primary = Color(0xFF424242), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF111111),
            secondary = Color(0xFF575757), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE4E4E4), onSecondaryContainer = Color(0xFF1C1C1C),
            tertiary = Color(0xFF4F4F4F), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFEAEAEA), onTertiaryContainer = Color(0xFF191919),
        ),
        dark = AccentColors(
            primary = Color(0xFFC2C2C2), onPrimary = Color(0xFF2C2C2C),
            primaryContainer = Color(0xFF595959), onPrimaryContainer = Color(0xFFE0E0E0),
            secondary = Color(0xFFC6C6C6), onSecondary = Color(0xFF303030),
            secondaryContainer = Color(0xFF474747), onSecondaryContainer = Color(0xFFE4E4E4),
            tertiary = Color(0xFFC4C4C4), onTertiary = Color(0xFF313131),
            tertiaryContainer = Color(0xFF494949), onTertiaryContainer = Color(0xFFEAEAEA),
        ),
    ),
    SKY(
        "sky", "空",
        light = AccentColors(
            primary = Color(0xFF0061A4), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
            secondary = Color(0xFF535F70), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD7E3F7), onSecondaryContainer = Color(0xFF101C2B),
            tertiary = Color(0xFF6B5778), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF3DAFF), onTertiaryContainer = Color(0xFF251431),
        ),
        dark = AccentColors(
            primary = Color(0xFF9FCAFF), onPrimary = Color(0xFF003258),
            primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF),
            secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253140),
            secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F7),
            tertiary = Color(0xFFD6BEE4), onTertiary = Color(0xFF3B2948),
            tertiaryContainer = Color(0xFF52405F), onTertiaryContainer = Color(0xFFF3DAFF),
        ),
    ),
    ORANGE(
        "orange", "橙",
        light = AccentColors(
            primary = Color(0xFF964900), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDCC2), onPrimaryContainer = Color(0xFF311300),
            secondary = Color(0xFF765846), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDCC2), onSecondaryContainer = Color(0xFF2D1608),
            tertiary = Color(0xFF626033), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE9E5AC), onTertiaryContainer = Color(0xFF1E1D00),
        ),
        dark = AccentColors(
            primary = Color(0xFFFFB877), onPrimary = Color(0xFF4C2700),
            primaryContainer = Color(0xFF713700), onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFE5BFA8), onSecondary = Color(0xFF442B1B),
            secondaryContainer = Color(0xFF5D4131), onSecondaryContainer = Color(0xFFFFDCC2),
            tertiary = Color(0xFFCAC892), onTertiary = Color(0xFF323100),
            tertiaryContainer = Color(0xFF494824), onTertiaryContainer = Color(0xFFE9E5AC),
        ),
    ),
    SAKURA(
        "sakura", "桜",
        light = AccentColors(
            primary = Color(0xFF984061), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E1), onPrimaryContainer = Color(0xFF3E001D),
            secondary = Color(0xFF75565C), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFD9DE), onSecondaryContainer = Color(0xFF2B151A),
            tertiary = Color(0xFF7B5633), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDDB8), onTertiaryContainer = Color(0xFF2D1600),
        ),
        dark = AccentColors(
            primary = Color(0xFFFFB1C2), onPrimary = Color(0xFF5E1133),
            primaryContainer = Color(0xFF7B2949), onPrimaryContainer = Color(0xFFFFD9E1),
            secondary = Color(0xFFE5BDC1), onSecondary = Color(0xFF43292D),
            secondaryContainer = Color(0xFF5A3F43), onSecondaryContainer = Color(0xFFFFD9DE),
            tertiary = Color(0xFFECBC90), onTertiary = Color(0xFF48280C),
            tertiaryContainer = Color(0xFF613E21), onTertiaryContainer = Color(0xFFFFDDB8),
        ),
    ),
    ;

    companion object {
        /** 設定に保存された ID → パレット。未知 ID は既定 (インディゴ) に落とす。 */
        fun byId(id: String?): AppPalette = entries.firstOrNull { it.id == id } ?: INDIGO
    }
}

/** 中立骨格のスキームにパレットのアクセントを載せる。 */
fun themedScheme(base: ColorScheme, accent: AccentColors): ColorScheme = base.copy(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary,
    onSecondary = accent.onSecondary,
    secondaryContainer = accent.secondaryContainer,
    onSecondaryContainer = accent.onSecondaryContainer,
    tertiary = accent.tertiary,
    onTertiary = accent.onTertiary,
    tertiaryContainer = accent.tertiaryContainer,
    onTertiaryContainer = accent.onTertiaryContainer,
)
