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

// パレット方針: 夜の時計 = 深いインディゴを主色に、選択状態(チップ/ナビピル)は
// 同系のくすみインディゴで揃える (secondaryContainer が M3 の標準選択色のため)。
// ニュートラルは彩度を落としたブルーグレー系で、light/dark 両方で 4.5:1 を確保する。
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
    background = Color(0xFFF6F6FA),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF6F6FA),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E4EC),
    onSurfaceVariant = Color(0xFF595963),
    outline = Color(0xFF75757F),
    outlineVariant = Color(0xFFCFCFD9),
    surfaceDim = Color(0xFFD8D8DE),
    surfaceBright = Color(0xFFF6F6FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F1F6),
    surfaceContainer = Color(0xFFEBE9F0),
    surfaceContainerHigh = Color(0xFFE6E4EA),
    surfaceContainerHighest = Color(0xFFE0DEE4),
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
    background = Color(0xFF111118),
    onBackground = Color(0xFFE5E5EC),
    surface = Color(0xFF111118),
    onSurface = Color(0xFFE5E5EC),
    surfaceVariant = Color(0xFF45454F),
    onSurfaceVariant = Color(0xFFB9B9C5),
    outline = Color(0xFF82828E),
    outlineVariant = Color(0xFF45454F),
    surfaceDim = Color(0xFF111118),
    surfaceBright = Color(0xFF37373F),
    surfaceContainerLowest = Color(0xFF0B0B10),
    surfaceContainerLow = Color(0xFF191920),
    surfaceContainer = Color(0xFF1E1E26),
    surfaceContainerHigh = Color(0xFF282830),
    surfaceContainerHighest = Color(0xFF33333B),
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
