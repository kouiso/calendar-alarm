package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * カレンダーイベント・単発アラーム → AlarmInstance への展開ロジック。
 * 鳴動タイミングの全判断はここに集約し、OS 側は展開結果をそのまま予約するだけにする。
 */
object AlarmExpander {

    /**
     * 過去の鳴動時刻をどれだけ遡って「未鳴動のまま残すか」。
     * この幅より古い未鳴動インスタンスは MISSED 扱いにして再鳴動させない。
     */
    val FIRE_GRACE: Duration = 60.minutes

    /**
     * イベント群を AlarmInstance に展開する。
     *
     * @param events プラットフォーム側で期間内に展開済みのイベント（繰り返し回を含む）
     * @param calendarRules カレンダー単位の鳴動ルール
     * @param overrides イベント単位の上書き（ミュート/分数）
     * @param disabledCalendarIds 無効化されたカレンダー
     * @param now 現在時刻
     * @param horizon 展開の上限時刻（now より後）
     */
    fun expandEvents(
        events: List<CalendarEvent>,
        calendarRules: Map<String, AlarmRule>,
        overrides: Map<String, EventOverride>,
        disabledCalendarIds: Set<String>,
        now: Instant,
        horizon: Instant,
    ): List<AlarmInstance> {
        val result = mutableListOf<AlarmInstance>()
        for (event in events) {
            if (event.calendarId in disabledCalendarIds) continue
            if (event.allDay) {
                // 終日イベントは startMillis が UTC 0時。現地日付の 9:00 換算に倒す設計は
                // 後続フェーズで検討する。MVP ではプロバイダ基準の start をそのまま使う。
            }
            val rule = calendarRules[event.calendarId] ?: AlarmRule()
            val override = overrides[event.instanceKey]
            if (override?.muted == true || !rule.enabled) continue
            val minutesBefore = override?.minutesBefore ?: rule.minutesBefore
            val triggerAt = Instant.fromEpochMilliseconds(event.startMillis) - minutesBefore.minutes
            if (!inWindow(triggerAt, now, horizon)) continue
            result += AlarmInstance(
                id = "ev:${event.instanceKey}:b$minutesBefore",
                triggerAtMillis = triggerAt.toEpochMilliseconds(),
                title = event.title.ifBlank { "(タイトルなし)" },
                kind = AlarmKind.EVENT,
                eventId = event.instanceKey,
                minutesBefore = minutesBefore,
                eventStartMillis = event.startMillis,
            )
        }
        return result
    }

    /**
     * 単発/曜日繰り返しアラームを [days] 日分展開する。
     * 例外日はスキップ、過去時刻はグレース幅だけ残す。
     */
    fun expandStandalone(
        alarm: StandaloneAlarm,
        now: Instant,
        days: Int = 14,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<AlarmInstance> {
        if (!alarm.enabled) return emptyList()
        val nowLocal = now.toLocalDateTime(zone)
        val horizon = now + days.days
        val result = mutableListOf<AlarmInstance>()
        val today = nowLocal.date
        for (i in 0..days) {
            val date = today.plus(i, kotlinx.datetime.DateTimeUnit.DAY)
            if (date in alarm.exceptions) {
                if (alarm.daysOfWeek.isEmpty()) break // 単発: 例外日を超えても未来回は作らない
                continue
            }
            if (alarm.daysOfWeek.isNotEmpty() && date.dayOfWeek !in alarm.daysOfWeek) continue
            val triggerLocal = LocalDateTime(date.year, date.month, date.dayOfMonth, alarm.hour, alarm.minute)
            val triggerAt = triggerLocal.toInstant(zone)
            // 単発: 最初に窓に入る回 (今日または明日以降) を採用。
            // 「今日の時刻が既に過ぎた」= 翌日の同時刻に鳴らすのが期待値。
            if (!inWindow(triggerAt, now, horizon)) continue
            result += AlarmInstance(
                id = "sa:${alarm.id}:${date}",
                triggerAtMillis = triggerAt.toEpochMilliseconds(),
                title = alarm.label.ifBlank { "アラーム" },
                kind = AlarmKind.STANDALONE,
                standaloneAlarmId = alarm.id,
                snoozeMinutes = alarm.snoozeMinutes,
                soundUri = alarm.soundUri,
            )
            if (alarm.daysOfWeek.isEmpty()) break
        }
        return result
    }

    /** 指定時刻が「今すぐ鳴らすべき／未来に予約すべき」窓に入るか。 */
    fun inWindow(triggerAt: Instant, now: Instant, horizon: Instant): Boolean {
        if (triggerAt > horizon) return false
        // 過去: グレース幅内なら即時鳴動させるため残す。それより古いものは捨てる
        return triggerAt > now - FIRE_GRACE
    }

    /** タイマー用の単発インスタンスを作る。 */
    fun timerInstance(durationMillis: Long, now: Instant, label: String = "タイマー"): AlarmInstance =
        AlarmInstance(
            id = "tm:${now.toEpochMilliseconds()}",
            triggerAtMillis = now.toEpochMilliseconds() + durationMillis,
            title = label,
            kind = AlarmKind.TIMER,
        )

    private val Int.days: Duration get() = Duration.parse("${this}d")
}
