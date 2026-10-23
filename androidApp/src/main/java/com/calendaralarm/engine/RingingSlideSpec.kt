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
}
