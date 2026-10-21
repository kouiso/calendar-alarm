package com.calendaralarm.shared.logic

/**
 * 予定画面に1行で出す「鳴動の状態」(#51 PR2)。
 * 鳴らない原因の重い順に1つだけ選ぶ: 許可が足りない → アラームの音量が0 → 最近鳴らせなかった → 予約が無い → 準備OK。
 * Android と iOS で文言と優先順を揃えるため shared に置く。
 */
object RingStatus {
    enum class Kind { MISSING_PERMISSION, ALARM_MUTED, MISSED_RECENTLY, NO_ALARM, READY }

    data class Summary(val kind: Kind, val missingPermissions: Int, val recentMissed: Int) {
        /** 予定画面の1行 */
        val line: String
            get() = when (kind) {
                Kind.MISSING_PERMISSION -> "許可が${missingPermissions}つ足りません。このままだと鳴らないことがあります"
                Kind.ALARM_MUTED -> "アラームの音量が0です。鳴っても音が出ません"
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
     * 直近24時間の見逃しを数える範囲。鳴動時刻 (予約の triggerAtMillis) がこの範囲に入る「鳴らず」を数える。
     * 数える元は鳴動の結果だけの記録 (Android は鳴動ログの MISS 行、iOS は ringHistory)。
     * 予約の行は単発アラームを使い直すと消え、iOS の監査ログは件数で古い順に消えるため、どちらでも数えない
     */
    const val MISSED_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /**
     * @param alarmMuted 端末のアラーム音量が0か。鳴動はアラームの音量で鳴るので、許可がそろっていても音が出ない。
     *   音量を読めない OS (iOS) は false のまま渡す
     */
    fun summarize(missingPermissions: Int, recentMissed: Int, hasNextAlarm: Boolean, alarmMuted: Boolean = false): Summary {
        val kind = when {
            missingPermissions > 0 -> Kind.MISSING_PERMISSION
            alarmMuted -> Kind.ALARM_MUTED
            recentMissed > 0 -> Kind.MISSED_RECENTLY
            !hasNextAlarm -> Kind.NO_ALARM
            else -> Kind.READY
        }
        return Summary(kind, missingPermissions.coerceAtLeast(0), recentMissed.coerceAtLeast(0))
    }
}
