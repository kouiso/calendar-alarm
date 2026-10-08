package com.calendaralarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.R

// Night UI デザインスペック準拠のニュートラル (doc/design: 濃紺ベース+琥珀アクセント)。
// アクセント系は Palettes.kt の10色から themedScheme で上書きするため、ここは中立色のみ。
// デザイン値: bg #0A0C11, card #12151C, raised #161A23, divider #1F2430,
//           text #ECEEF3, muted #9AA3B2, border #262C38 (dark)。
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A46D4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF120D69),
    secondary = Color(0xFF585A92),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF1A1B4B),
    tertiary = Color(0xFF006A63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E7),
    onTertiaryContainer = Color(0xFF00201D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF14171F),
    surface = Color(0xFFF4F5F8),
    onSurface = Color(0xFF14171F),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF5B6372),
    outline = Color(0xFFD7D8E3),
    outlineVariant = Color(0xFFE6E8EE),
    surfaceDim = Color(0xFFE6E8EE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEFF4),
    surfaceContainerHighest = Color(0xFFE6E8EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDBDFF),
    onPrimary = Color(0xFF26247A),
    primaryContainer = Color(0xFF3B3794),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = Color(0xFFC4C3EA),
    onSecondary = Color(0xFF2D2E55),
    secondaryContainer = Color(0xFF444462),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFF86D2C7),
    onTertiary = Color(0xFF003730),
    tertiaryContainer = Color(0xFF00504A),
    onTertiaryContainer = Color(0xFF9EF2E7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0C11),
    onBackground = Color(0xFFECEEF3),
    surface = Color(0xFF0A0C11),
    onSurface = Color(0xFFECEEF3),
    surfaceVariant = Color(0xFF262C38),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF262C38),
    outlineVariant = Color(0xFF1F2430),
    surfaceDim = Color(0xFF0A0C11),
    surfaceBright = Color(0xFF1F2430),
    surfaceContainerLowest = Color(0xFF0D0F15),
    surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A23),
    surfaceContainerHigh = Color(0xFF1A1F2A),
    surfaceContainerHighest = Color(0xFF1F2430),
)

// アプリ共通書体: IBM Plex Sans JP (英字/和文が一体設計)。
// システム CJK フォールバックはロケール次第で中国語字形を選ぶため、日本語字形を
// 保証するためにバンドルする (OFL)。
val AppFontFamily = FontFamily(
    Font(R.font.plex_sans_jp_light, FontWeight.Light),
    Font(R.font.plex_sans_jp_regular, FontWeight.Normal),
    Font(R.font.plex_sans_jp_medium, FontWeight.Medium),
    Font(R.font.plex_sans_jp_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_jp_semibold, FontWeight.Bold),
)

// 時刻・数字表示用書体: Outfit (Thin系幾何学サンセリフ、数字専用・OFL)。
// Night UI 仕様では大きな時刻は ExtraLight/Light、並びの時刻は Medium。
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_extralight, FontWeight.ExtraLight),
    Font(R.font.outfit_light, FontWeight.Light),
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
)

private val baseTypography = Typography()

// 全スタイルの fontFamily を AppFontFamily で統一 (未指定スタイルは platform 既定
// = 中国語字形フォールバックに戻ってしまう)。大きな時刻数字は薄めウェイト (時計
// アプリの定石)。
private val AppTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = AppFontFamily),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = AppFontFamily),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = baseTypography.labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = AppFontFamily),
)

// カードを全体的にやわらかく (Card=medium / BottomSheet・Dialog=large)
private val AppShapes = Shapes(
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CalendarAlarmTheme(
    themeId: String = "default",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = AppPalette.byId(themeId.takeIf { it != "default" })
    MaterialTheme(
        colorScheme = themedScheme(
            if (darkTheme) DarkColors else LightColors,
            if (darkTheme) palette.dark else palette.light,
        ),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
