package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.RepeatMode
import com.calendaralarm.shared.model.StandaloneAlarm
import com.calendaralarm.shared.model.TitleCodeSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.DateTimeUnit
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
     * @param zone 終日イベントの鳴動時刻を解決するローカルゾーン
     */
    fun expandEvents(
        events: List<CalendarEvent>,
        calendarRules: Map<String, AlarmRule>,
        overrides: Map<String, EventOverride>,
        disabledCalendarIds: Set<String>,
        now: Instant,
        horizon: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        // calendar_prefs 行が無いカレンダー用の既定ルール。
        // グローバル設定の「N分前」をここで効かせ、UI表示と実際の鳴動を一致させる。
        defaultRule: AlarmRule = AlarmRule(),
        titleCodes: TitleCodeSettings = TitleCodeSettings(),
        inviteFilter: InviteFilter = InviteFilter(),
        /** true のときイベントがカレンダーに持つリマインダーも鳴動対象にする。 */
        importEventReminders: Boolean = false,
        /** 元アプリの「一括ミュート」: ALARM 鳴動を全て抑止する。
         *  NOTIFY (通知のみ) は鳴動ではないので残す。 */
        muteAll: Boolean = false,
    ): List<AlarmInstance> {
        val result = mutableListOf<AlarmInstance>()
        for (event in events) {
            if (event.calendarId in disabledCalendarIds) continue
            val rule = calendarRules[event.calendarId] ?: defaultRule
            val override = overrides[event.instanceKey]
            if (!rule.enabled || override?.action == EventAction.MUTE) continue
            // 招待予定フィルタ。明示的に ALARM を選んだ予定はフィルタより優先
            // (個別指定がカレンダー横断の既定より具体的な意思表示のため)。
            if (override?.action != EventAction.ALARM &&
                !inviteFilter.allows(event.inviteStatus)
            ) continue
            // 終日イベントは startMillis が UTC 0時。深夜に鳴らさないため
            // イベント日のローカル allDayMinutes (既定9:00) に倒す。負数なら鳴らさない。
            val eventStart = if (event.allDay) {
                if (rule.allDayMinutes < 0) continue
                val day = Instant.fromEpochMilliseconds(event.startMillis)
                    .toLocalDateTime(TimeZone.UTC).date
                LocalDateTime(
                    day.year, day.month, day.dayOfMonth,
                    rule.allDayMinutes / 60, rule.allDayMinutes % 60,
                ).toInstant(zone).toEpochMilliseconds()
            } else {
                event.startMillis
            }
            val startMillis = Instant.fromEpochMilliseconds(eventStart)
            val minutesBefore = override?.minutesBefore ?: rule.minutesBefore
            val extras = override?.extraOffsets ?: rule.extraOffsets
            val (startAction, reminderAction) = resolveActions(event, rule, override, titleCodes)
            val title = event.title.ifBlank { "(タイトルなし)" }
            // triggerAt 重複を避けるため時刻単位で集約 (開始オフセットを優先)
            val seenTriggers = mutableSetOf<Long>()
            fun emit(minutes: Int, action: EventAction) {
                if (action == EventAction.MUTE) return
                if (muteAll && action == EventAction.ALARM) return
                val triggerAt = startMillis - minutes.minutes
                if (!inWindow(triggerAt, now, horizon)) return
                if (!seenTriggers.add(triggerAt.toEpochMilliseconds())) return
                result += AlarmInstance(
                    id = "ev:${event.instanceKey}:b$minutes",
                    triggerAtMillis = triggerAt.toEpochMilliseconds(),
                    title = title,
                    kind = AlarmKind.EVENT,
                    eventId = event.instanceKey,
                    minutesBefore = minutes,
                    eventStartMillis = eventStart,
                    delivery = action,
                )
            }
            emit(minutesBefore, startAction)
            extras.sorted().forEach { emit(it, reminderAction) }
            // カレンダー側リマインダーの取り込み (アプリ追加分と同じ trigger は重複除外)
            if (importEventReminders) {
                event.calendarReminderMinutes.sorted().forEach { emit(it, reminderAction) }
            }
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
        val mode = alarm.effectiveRepeatMode()
        return when (mode) {
            RepeatMode.ONCE, RepeatMode.WEEKLY -> expandWeeklyOrOnce(alarm, mode, now, days, zone)
            else -> expandInterval(alarm, mode, now, days, zone)
        }
    }

    /** 従来型: 日ループで曜日 (WEEKLY) または最初の1回 (ONCE) を拾う。 */
    private fun expandWeeklyOrOnce(
        alarm: StandaloneAlarm,
        mode: RepeatMode,
        now: Instant,
        days: Int,
        zone: TimeZone,
    ): List<AlarmInstance> {
        // WEEKLY なのに曜日が1つも選ばれていないと永遠に鳴らない。
        // 「毎週鳴らす意図に一番近いのは次の1回」として ONCE に倒す
        val effectiveMode = if (mode == RepeatMode.WEEKLY && alarm.daysOfWeek.isEmpty()) {
            RepeatMode.ONCE
        } else {
            mode
        }
        val nowLocal = now.toLocalDateTime(zone)
        val horizon = now + days.days
        val result = mutableListOf<AlarmInstance>()
        val today = nowLocal.date
        for (i in 0..days) {
            val date = today.plus(i, DateTimeUnit.DAY)
            if (date in alarm.exceptions) {
                if (effectiveMode == RepeatMode.ONCE) break // 単発: 例外日を超えても未来回は作らない
                continue
            }
            if (effectiveMode == RepeatMode.WEEKLY && date.dayOfWeek !in alarm.daysOfWeek) continue
            val triggerAt = triggerAt(alarm, date, zone) ?: continue
            if (!inWindow(triggerAt, now, horizon)) continue
            result += alarm.toInstance(date, triggerAt)
            if (effectiveMode == RepeatMode.ONCE) break
        }
        return result
    }

    /**
     * 周期型: 起点日から N日/N週/Nヶ月毎 (INTERVAL_*) または毎月同じ日 (MONTHLY) に展開。
     * 窓内に1回も無い場合でも「次の1回」を必ず出す (窓外の鳴動は消えないよう
     * スケジューラに渡す必要がある。例: 毎月15日で窓が14日の場合)。
     */
    private fun expandInterval(
        alarm: StandaloneAlarm,
        mode: RepeatMode,
        now: Instant,
        days: Int,
        zone: TimeZone,
    ): List<AlarmInstance> {
        val horizon = now + days.days
        val anchor = anchorDate(alarm, now, zone)
        val result = mutableListOf<AlarmInstance>()
        var date = firstOccurrenceOnOrAfter(alarm, mode, anchor, now.toLocalDateTime(zone).date)
        var guard = 0
        while (guard++ < 400) {
            if (date !in alarm.exceptions) {
                val triggerAt = triggerAt(alarm, date, zone)
                // グレース幅内の過去発生も残す (inWindow と同じ下限)。
                // 再起動直後の resync で周期アラームが無言 CANCEL されるのを防ぐ
                if (triggerAt != null && triggerAt > now - FIRE_GRACE) {
                    result += alarm.toInstance(date, triggerAt)
                    // 窓を超えた初回のみ emit して終わる (周期的に1件あれば十分)
                    if (triggerAt > horizon) break
                }
            }
            val next = nextOccurrence(mode, alarm.repeatInterval, anchor, date) ?: break
            if (next == date) break
            date = next
        }
        return result
    }

    private fun triggerAt(alarm: StandaloneAlarm, date: LocalDate, zone: TimeZone): Instant? =
        runCatching {
            LocalDateTime(date.year, date.month, date.dayOfMonth, alarm.hour, alarm.minute)
                .toInstant(zone)
        }.getOrNull()

    private fun StandaloneAlarm.toInstance(date: LocalDate, triggerAt: Instant) = AlarmInstance(
        id = "sa:$id:$date",
        triggerAtMillis = triggerAt.toEpochMilliseconds(),
        title = label.ifBlank { "アラーム" },
        kind = AlarmKind.STANDALONE,
        standaloneAlarmId = id,
        snoozeMinutes = snoozeMinutes,
        soundUri = soundUri,
        muteUntilUnlock = muteUntilUnlock,
    )

    /** 起点日。未設定なら「今日」を使う (MONTHLY はこの日付の日が毎月の鳴動日)。 */
    private fun anchorDate(alarm: StandaloneAlarm, now: Instant, zone: TimeZone): LocalDate =
        alarm.repeatAnchorMillis?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
        } ?: now.toLocalDateTime(zone).date

    /** [base] 以降の最初の発生日。過去の起点は周期分だけ前倒しする。 */
    private fun firstOccurrenceOnOrAfter(
        alarm: StandaloneAlarm,
        mode: RepeatMode,
        anchor: LocalDate,
        base: LocalDate,
    ): LocalDate {
        var d = anchor
        var guard = 0
        while (d < base && guard++ < 500) {
            val n = nextOccurrence(mode, alarm.repeatInterval, anchor, d) ?: break
            if (n <= d) break
            d = n
        }
        return d
    }

    /** 1ステップ先の発生日。MONTHLY は月末に丸める (31日設定でも2月に鳴る)。 */
    private fun nextOccurrence(mode: RepeatMode, interval: Int, anchor: LocalDate, from: LocalDate): LocalDate? =
        runCatching {
            when (mode) {
                RepeatMode.MONTHLY ->
                    plusMonthsClamped(from.plus(1, DateTimeUnit.MONTH), anchor.dayOfMonth)
                RepeatMode.INTERVAL_DAYS -> from.plus(interval.coerceAtLeast(1), DateTimeUnit.DAY)
                RepeatMode.INTERVAL_WEEKS -> from.plus(interval.coerceAtLeast(1) * 7, DateTimeUnit.DAY)
                RepeatMode.INTERVAL_MONTHS ->
                    plusMonthsClamped(from.plus(interval.coerceAtLeast(1), DateTimeUnit.MONTH), anchor.dayOfMonth)
                else -> null
            }
        }.getOrNull()

    /** [month] の中で dayOfMonth に丸めた日 (月末越えは月末日)。 */
    private fun plusMonthsClamped(month: LocalDate, dayOfMonth: Int): LocalDate {
        val lastDay = when (month.monthNumber) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (month.year % 4 == 0 && (month.year % 100 != 0 || month.year % 400 == 0)) 29 else 28
        }
        return LocalDate(month.year, month.monthNumber, minOf(dayOfMonth, lastDay))
    }

    /**
     * イベントの開始/リマインダーそれぞれの鳴動アクションを解決する。
     * 優先順位: 明示上書き > neverコード > alwaysコード > ルール既定。
     * never を always より優先する (抑止を破る方が危ない)。
     * アジェンダ表示側も同じ解決を使い、UIと実鳴動を一致させる。
     */
    fun resolveActions(
        event: CalendarEvent,
        rule: AlarmRule,
        override: EventOverride?,
        titleCodes: TitleCodeSettings,
    ): Pair<EventAction, EventAction> {
        val startAction = override?.action
            ?: when {
                titleCodes.applyToStart && titleCodes.neverMatch(event.title) -> EventAction.MUTE
                titleCodes.applyToStart && titleCodes.alwaysMatch(event.title) -> EventAction.ALARM
                else -> rule.startAction
            }
        val reminderAction = override?.action
            ?: when {
                titleCodes.applyToReminders && titleCodes.neverMatch(event.title) -> EventAction.MUTE
                titleCodes.applyToReminders && titleCodes.alwaysMatch(event.title) -> EventAction.ALARM
                else -> rule.reminderAction
            }
        return startAction to reminderAction
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
