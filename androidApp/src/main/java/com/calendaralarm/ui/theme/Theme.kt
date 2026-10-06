package com.calendaralarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ブランド色: 視認性重視の青。ダークでもコントラスト比4.5:1を確保
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B6CB0),
    onPrimary = Color.White,
    secondary = Color(0xFF4F8CFF),
    surface = Color(0xFFFAFAFA),
    background = Color(0xFFF5F5F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC6F5),
    onPrimary = Color(0xFF0A2038),
    secondary = Color(0xFF8AB8FF),
    surface = Color(0xFF1B1B1D),
    background = Color(0xFF141416),
)

@Composable
fun CalendarAlarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
