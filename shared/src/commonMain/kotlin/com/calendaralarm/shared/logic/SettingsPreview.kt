package com.calendaralarm.shared.logic

/**
 * 設定の選択肢を「文字だけ」にせず、選んだ結果の時刻で見せるための計算
 * (局長決定 2026-10-17 の5)。例の予定・例のアラームに当てはめた時刻を返す。
 */
object SettingsPreview {
    /** 例に使う予定の開始 (9:00)。 */
    const val SAMPLE_EVENT_MINUTE = 9 * 60

    /** 例に使うアラームの時刻 (6:30)。 */
    const val SAMPLE_ALARM_MINUTE = 6 * 60 + 30

    /** [eventMinuteOfDay] の予定を [minutesBefore] 分前に鳴らす時の時刻 ("8:50")。日をまたげば前日の時刻。 */
    fun ringClock(eventMinuteOfDay: Int, minutesBefore: Int): String =
        clock(eventMinuteOfDay - minutesBefore)

    /** [alarmMinuteOfDay] に鳴ったアラームを [snoozeMinutes] 分スヌーズした時の再鳴動時刻。 */
    fun snoozeClock(alarmMinuteOfDay: Int, snoozeMinutes: Int): String =
        clock(alarmMinuteOfDay + snoozeMinutes)

    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
