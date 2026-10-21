package com.calendaralarm.shared.logic

/**
 * 予定画面に1行で出す「鳴動の状態」(#51 PR2)。
 * 鳴らない原因の重い順に1つだけ選ぶ: 許可が足りない → 最近鳴らせなかった → 予約が無い → 準備OK。
 * Android と iOS で文言と優先順を揃えるため shared に置く。
 */
object RingStatus {
    enum class Kind { MISSING_PERMISSION, MISSED_RECENTLY, NO_ALARM, READY }

    data class Summary(val kind: Kind, val missingPermissions: Int, val recentMissed: Int) {
        /** 予定画面の1行 */
        val line: String
            get() = when (kind) {
                Kind.MISSING_PERMISSION -> "許可が${missingPermissions}つ足りません。このままだと鳴らないことがあります"
                Kind.MISSED_RECENTLY -> "直近24時間に鳴らせなかったアラームが${recentMissed}件あります"
                Kind.NO_ALARM -> "予約中のアラームはありません"
                Kind.READY -> "鳴る準備ができています"
            }

        /** 許可が足りない時のバナーを出すか */
        val showPermissionBanner: Boolean get() = missingPermissions > 0

        /** 鳴らせなかった時のバナーを出すか (許可のバナーと同時に出してよい) */
        val showMissedBanner: Boolean get() = recentMissed > 0
    }

    /**
     * 直近24時間の見逃しを数える範囲。数えるのは予約の行 (鳴らせなかった = MISSED、7日残る) で、
     * 件数で古い順に消える監査ログでは数えない (24時間より前に押し出されて見逃しが消えるため)
     */
    const val MISSED_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    fun summarize(missingPermissions: Int, recentMissed: Int, hasNextAlarm: Boolean): Summary {
        val kind = when {
            missingPermissions > 0 -> Kind.MISSING_PERMISSION
            recentMissed > 0 -> Kind.MISSED_RECENTLY
            !hasNextAlarm -> Kind.NO_ALARM
            else -> Kind.READY
        }
        return Summary(kind, missingPermissions.coerceAtLeast(0), recentMissed.coerceAtLeast(0))
    }
}
