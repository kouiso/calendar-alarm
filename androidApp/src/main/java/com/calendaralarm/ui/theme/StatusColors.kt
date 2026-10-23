package com.calendaralarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 状態を伝える色 (#51 デザイン改修の色表)。テーマ色は「鳴る/オン」専用なので、
 * 準備OK・警告・今の時刻の線はテーマを替えても変わらない固定色にする。
 * iOS は NightTheme の同名の色と同じ値。
 */
object StatusColors {
    val ok: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FD6A6) else Color(0xFF18734B)

    val warn: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFB3261E)

    val warnBackground: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0x1AFF8A80) else Color(0xFFFDECEA)

    /** 時間軸の今の時刻の線 */
    val nowLine: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFD32F2F)
}
