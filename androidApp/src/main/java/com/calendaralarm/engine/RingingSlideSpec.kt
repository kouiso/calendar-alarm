package com.calendaralarm.engine

/**
 * 鳴動画面 (Compose の RingingActivity) と代わりの画面 (View の RingingOverlay) が
 * 同じ寸法で組むための値 (dp)。iOS の RingingView.swift (丸64・余白8・高さ80) とも同じにする。
 * 掴める幅は SlideToStopRule が丸と余白から決めるので、ここを変えれば両画面とテストが一緒に動く。
 */
internal object RingingSlideSpec {
    const val THUMB_DP = 64
    const val PAD_DP = 8
    const val TRACK_HEIGHT_DP = 80
    const val SNOOZE_HEIGHT_DP = 80
    const val STOP_ICON_DP = 24
    const val TRACK_CORNER_DP = TRACK_HEIGHT_DP / 2

    // 大きいボタンモード (#154)。盤 p154 の 2 列目 (.bb / .dial.sm / .rt) の値で、盤の 1px = 1dp。
    // スヌーズと停止は同じ大きさ (.bb: 高さ124・角丸32・24px・太さ600)、ボタンの間は 12
    const val LARGE_BUTTON_HEIGHT_DP = 124
    const val LARGE_BUTTON_CORNER_DP = 32
    const val LARGE_BUTTON_TEXT_SP = 24
    const val LARGE_BUTTON_GAP_DP = 12
    // 文字盤は 176 (通常は 290 のまま)、時刻は 66、予定名は 22
    const val LARGE_DIAL_DP = 176
    const val LARGE_DIAL_TIME_SP = 66
    const val LARGE_TITLE_SP = 22
}
