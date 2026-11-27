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

    // 鳴動画面 (Compose) と代わりの画面 (View) が同じ位置・大きさ・色で組むための値 (#336)。
    // 両画面はここから引くので、片方だけ値を変えるとズレ検知のテスト (RingingOverlayMatchesScreenTest) が落ちる
    // 画面の端
    const val SCREEN_PAD_H_DP = 24
    const val SCREEN_PAD_V_DP = 20

    // 日付の札
    const val DATE_TEXT_SP = 13
    const val DATE_HEIGHT_DP = 40
    const val DATE_PAD_H_DP = 18
    const val DATE_CORNER_DP = 14

    // 二重の輪
    const val DIAL_DP = 290
    const val DIAL_INNER_DP = 230
    const val LARGE_DIAL_INNER_DP = 140
    const val DIAL_TIME_PER_DIAL = 112f / 290f
    // 時刻の字の間 (外の輪の大きさに掛ける)
    const val DIAL_TIME_LETTER_SPACING_PER_DIAL = -4f / 290f
    const val TIME_PATTERN = "H:mm"
    const val DIAL_TEXT_RESERVE_DP = 170
    const val DIAL_MIN_SCALE = 0.35f
    const val DIAL_OUTER_STROKE_DP = 1.5f
    const val DIAL_INNER_STROKE_DP = 1f
    const val DIAL_OUTER_ALPHA = 0.25f
    const val DIAL_INNER_ALPHA = 0.15f
    // 輪と「○分後に開始」の間 (輪の縮みに合わせて掛ける)
    const val DIAL_TO_STARTS_IN_DP = 18

    // 「○分後に開始」と題
    const val STARTS_IN_TEXT_SP = 15
    const val STARTS_IN_HEIGHT_DP = 24
    const val STARTS_IN_TO_TITLE_DP = 8
    const val TITLE_SP = 32
    const val TITLE_LINE_SP = 42
    // 22 × 1.35
    const val LARGE_TITLE_LINE_SP = 29.7f

    // 下の操作群 (高さは Compose の文字の行の高さに依らないよう両画面で固定する)
    const val SNOOZE_TEXT_SP = 22
    const val SNOOZE_CHOOSE_HEIGHT_DP = 40
    const val SNOOZE_CHOOSE_TEXT_SP = 14
    const val STOP_GAP_DP = 12
    const val SLIDE_LABEL_SP = 20
    const val FOOT_GAP_DP = 10
    const val FOOT_HEIGHT_DP = 24
    const val FOOT_TEXT_SP = 12

    // 色 (ARGB)。テーマ色は AppPalette.forRinging から引く
    const val BACKGROUND_ARGB: Int = 0xFF0A0C11.toInt()
    const val TEXT_ARGB: Int = 0xFFF4F5F8.toInt()
    const val BUTTON_TEXT_ARGB: Int = 0xFFECEEF3.toInt()
    const val SUB_TEXT_ARGB: Int = 0xFFB7BECB.toInt()
    const val FOOT_TEXT_ARGB: Int = 0xFF6E7686.toInt()
    // 白 8%
    const val BUTTON_FILL_ARGB: Int = 0x14FFFFFF
    // 白 6%
    const val DATE_FILL_ARGB: Int = 0x0FFFFFFF
    // 白 10%
    const val DATE_STROKE_ARGB: Int = 0x1AFFFFFF
    const val TRACK_STROKE_ALPHA = 0.35f

    /** 輪を縮める割合。[areaHeightDp] は輪・「○分後に開始」・題を入れる枠の高さ */
    fun dialScale(areaHeightDp: Float, largeButtons: Boolean): Float {
        val base = (if (largeButtons) LARGE_DIAL_DP else DIAL_DP).toFloat()
        return ((areaHeightDp - DIAL_TEXT_RESERVE_DP) / base).coerceIn(DIAL_MIN_SCALE, 1f)
    }

    /** 外の輪の大きさ (dp)。縮めた大きさと枠の幅の小さい方 */
    fun dialOuterDp(areaHeightDp: Float, areaWidthDp: Float, largeButtons: Boolean): Float {
        val base = (if (largeButtons) LARGE_DIAL_DP else DIAL_DP).toFloat()
        return minOf(base * dialScale(areaHeightDp, largeButtons), areaWidthDp)
    }

    /** 内の輪の割合。通常 230/290、大きいボタン 140/176 */
    fun dialInnerRatio(largeButtons: Boolean): Float =
        if (largeButtons) LARGE_DIAL_INNER_DP / LARGE_DIAL_DP.toFloat() else DIAL_INNER_DP / DIAL_DP.toFloat()

    /** 時刻の文字の大きさの割合。通常 112/290、大きいボタン 66/176 */
    fun dialTimeRatio(largeButtons: Boolean): Float =
        if (largeButtons) LARGE_DIAL_TIME_SP / LARGE_DIAL_DP.toFloat() else DIAL_TIME_PER_DIAL
}
