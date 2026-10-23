package com.calendaralarm.shared.logic

/**
 * 鳴動画面の「スライドして停止」の判定 (#52)。
 * Android と iOS で掴める幅と止まる位置がずれないよう、両方の画面がここを読む。
 */
object SlideToStopRule {
    /** 丸をトラックの何割まで引いたら止めるか。途中で離した誤操作では止めないため8割にする。 */
    const val STOP_THRESHOLD: Float = 0.8f

    /** 掴める幅は丸の直径に余白をこの倍数だけ足した所まで。丸の少し右を触っても掴めるようにする。 */
    const val GRAB_PAD_MULTIPLIER: Float = 3f

    /** トラック左端から [startX] の所に指を置いた時、丸を掴んだことにするか。 */
    fun isGrab(startX: Float, thumbSize: Float, pad: Float): Boolean =
        startX <= thumbSize + pad * GRAB_PAD_MULTIPLIER

    /** 引いた割合 [progress] (0〜1) で指を離した時に止めるか。 */
    fun shouldStop(progress: Float): Boolean = progress >= STOP_THRESHOLD
}
