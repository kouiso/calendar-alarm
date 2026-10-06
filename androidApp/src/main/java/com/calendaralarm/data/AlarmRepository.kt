package com.calendaralarm.data

import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.data.db.StandaloneAlarmEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.AlarmPlanner
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

/**
 * 鳴動ドメインの統合リポジトリ。
 * 「理想状態の展開 → 差分 → AlarmManager/DB への適用」を一手に引き受ける。
 */
private fun String?.toOffsets(): List<Int> =
    this?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 } ?: emptyList()

private fun List<Int>.toCsv(): String = distinct().sorted().joinToString(",")

class AlarmRepository(
    private val db: AppDatabase,
    private val scheduler: AlarmScheduler,
    private val calendarReader: CalendarContractReader,
    private val settings: SettingsRepository,
    private val hasCalendarPermission: () -> Boolean,
    private val appScope: CoroutineScope,
    /** 予約が変わった時の呼び出し (ウィジェット更新用)。 */
    private val onScheduleChanged: (suspend () -> Unit)? = null,
) {
    // ---- 参照 ----

    fun pendingFlow(): Flow<List<AlarmInstance>> =
        db.scheduledInstances().pendingFlow().map { rows -> rows.map { it.toInstance() } }

    fun instanceFlow(id: String): Flow<AlarmInstance?> =
        db.scheduledInstances().allFlow().map { rows ->
            rows.firstOrNull { it.id == id }?.toInstance()
        }

    suspend fun instanceById(id: String): AlarmInstance? =
        db.scheduledInstances().byId(id)?.toInstance()

    fun standaloneAlarmsFlow(): Flow<List<StandaloneAlarm>> =
        db.standaloneAlarms().allFlow().map { rows -> rows.map { it.toModel() } }

    suspend fun setState(id: String, state: AlarmState) =
        db.scheduledInstances().setState(id, state.name)

    suspend fun audit(action: String, detail: String) = withContext(Dispatchers.IO) {
        db.auditLog().insert(
            AuditLogEntity(atMillis = System.currentTimeMillis(), action = action, detail = detail),
        )
    }

    fun auditFlow(limit: Int = 100) = db.auditLog().recentFlow(limit)

    /** アジェンダ画面用: イベント + 有効な鳴動ルール。 */
    data class AgendaItem(
        val event: com.calendaralarm.shared.model.CalendarEvent,
        val calendarName: String,
        val calendarColor: Int,
        val muted: Boolean,
        val minutesBefore: Int,
        val extraOffsets: List<Int>,
        val hasOverride: Boolean,
    )

    suspend fun upcomingEvents(days: Int = 14): List<AgendaItem> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext emptyList()
        val now = Clock.System.now()
        val sources = calendarReader.calendars().associateBy { it.id }
        val prefs = db.calendarPrefs().all().associateBy { it.calendarId }
        val overrides = db.eventOverrides().all().associateBy { it.instanceKey }
        val events = calendarReader.events(
            now.toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds,
            (now + days.days).toEpochMilliseconds(),
        )
        events.map { ev ->
            val pref = prefs[ev.calendarId]
            val ov = overrides[ev.instanceKey]
            AgendaItem(
                event = ev,
                calendarName = sources[ev.calendarId]?.name ?: ev.calendarId,
                calendarColor = sources[ev.calendarId]?.color ?: 0xFF888888.toInt(),
                muted = ov?.muted ?: (pref?.enabled == false),
                minutesBefore = ov?.minutesBefore ?: pref?.minutesBefore
                    ?: settings.flow.first().defaultMinutesBefore,
                extraOffsets = ov?.extraOffsetsCsv?.toOffsets()
                    ?: pref?.extraOffsetsCsv.toOffsets(),
                hasOverride = ov != null,
            )
        }
    }

    suspend fun calendarSources() = withContext(Dispatchers.IO) {
        calendarReader.calendars()
    }

    suspend fun calendarPrefsFlowList() = db.calendarPrefs().all()

    // ---- 鳴動イベント ----

    suspend fun onFired(id: String) {
        setState(id, AlarmState.FIRED)
        audit("FIRE", id)
        onScheduleChanged?.invoke()
    }

    suspend fun onDismissed(id: String?) {
        if (id == null) return
        setState(id, AlarmState.DISMISSED)
        scheduler.cancel(id)
        audit("DISMISS", id)
        onScheduleChanged?.invoke()
    }

    suspend fun onSnoozed(id: String?) {
        val entity = id?.let { db.scheduledInstances().byId(it) } ?: return
        val instance = entity.toInstance()
        val snoozed = instance.snoozed(
            Clock.System.now().toEpochMilliseconds() + instance.snoozeMinutes * 60_000L,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(snoozed)))
        if (!scheduler.schedule(snoozed)) audit("EXACT_DENIED", snoozed.id)
        setState(instance.id, AlarmState.SNOOZED)
        audit("SNOOZE", "${instance.id} +${instance.snoozeMinutes}m → ${snoozed.id}")
        onScheduleChanged?.invoke()
    }

    /** タイマーなど単発インスタンスを即座に予約する。 */
    suspend fun scheduleAdhoc(instance: AlarmInstance) = withContext(Dispatchers.IO) {
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(instance)))
        if (!scheduler.schedule(instance)) {
            setState(instance.id, AlarmState.CANCELLED)
            audit("EXACT_DENIED", "adhoc ${instance.id}")
            return@withContext
        }
        audit("SCHEDULE", "adhoc ${instance.id} @${instance.triggerAtMillis}")
        onScheduleChanged?.invoke()
    }

    // ---- 展開・適用 ----

    /**
     * 全ソースを再展開して予約を理想状態に合わせる。
     * カレンダー権限が無くても単発アラームは展開する（部分動作を優先）。
     */
    suspend fun resync(reason: String) = withContext(Dispatchers.IO) {
        val now = Clock.System.now()
        val horizon = now + 14.days
        val desired = mutableListOf<AlarmInstance>()

        // 単発/繰り返しアラーム
        val standalones = db.standaloneAlarms().all().map { it.toModel() }
        standalones.forEach { desired += AlarmExpander.expandStandalone(it, now = now, days = 14) }

        // カレンダーイベント
        if (hasCalendarPermission()) {
            val prefs = db.calendarPrefs().all().associate {
                it.calendarId to AlarmRule(
                    enabled = it.enabled,
                    minutesBefore = it.minutesBefore,
                    allDayMinutes = it.allDayMinutes,
                    extraOffsets = it.extraOffsetsCsv.toOffsets(),
                )
            }
            val overrides = db.eventOverrides().all().associate {
                it.instanceKey to EventOverride(
                    muted = it.muted,
                    minutesBefore = it.minutesBefore,
                    extraOffsets = it.extraOffsetsCsv?.toOffsets(),
                )
            }
            val disabledCalIds = prefs.filterValues { !it.enabled }.keys
            val events = calendarReader.events(
                now.toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds,
                horizon.toEpochMilliseconds(),
            )
            desired += AlarmExpander.expandEvents(
                events = events,
                calendarRules = prefs,
                overrides = overrides,
                disabledCalendarIds = disabledCalIds,
                now = now, horizon = horizon,
                zone = TimeZone.currentSystemDefault(),
            )
        }

        // 終了済み状態 (鳴動/停止/スヌーズ/見逃し) の同 id は蘇生させない。
        // 展開が同 id を再発行しても、ユーザーが「止めた」「スヌーズした」意味を守る。
        val allRows = db.scheduledInstances().all()
        val terminalIds = allRows.asSequence()
            .filter {
                it.state in setOf(
                    AlarmState.FIRED.name, AlarmState.DISMISSED.name,
                    AlarmState.SNOOZED.name, AlarmState.MISSED.name,
                )
            }
            .map { it.id }
            .toSet()
        desired.removeAll { it.id in terminalIds }

        // タイマー・スヌーズなど Expander が再生しない予約は「理想状態」へ持ち越す。
        // 持ち越さないと差分計算で毎 resync キャンセルされてしまう。
        val pendingRows = allRows.filter { it.state == AlarmState.PENDING.name }
        val adhoc = pendingRows.map { it.toInstance() }
            .filter { it.kind == AlarmKind.TIMER || it.snoozeSeq > 0 }
        val desiredIds = desired.map { it.id }.toSet()
        desired += adhoc.filter { it.id !in desiredIds }

        val scheduled = pendingRows.associate { it.id to it.triggerAtMillis }
        val plan = AlarmPlanner.plan(scheduled, desired, now)
        applyPlan(plan, desired, reason)
    }

    private suspend fun applyPlan(
        plan: AlarmPlanner.Plan,
        desired: List<AlarmInstance>,
        reason: String,
    ) {
        for (id in plan.toCancel) {
            scheduler.cancel(id)
            setState(id, AlarmState.CANCELLED)
        }
        // 差分が無い予約も毎回 AlarmManager に再主張する。
        // 再起動・強制終了・パッケージ更新で OS 側だけ消えるケースを潰すため。
        // 近い順に予約する。AlarmManager の同時予約上限 (500) に万が一
        // 触れた場合、最も遠い予約から落ちる退化になる (近い側が鳴れば
        // 次回 resync で空きができて遠い側が拾い直される)。
        val fireNowIds = plan.toFireNow.map { it.id }.toSet()
        val toSchedule = desired.filter { it.id !in fireNowIds }
            .sortedBy { it.triggerAtMillis }
        if (toSchedule.isNotEmpty()) {
            db.scheduledInstances().upsert(toSchedule.map { ScheduledInstanceEntity.of(it) })
            toSchedule.forEach {
                if (!scheduler.schedule(it)) {
                    audit("EXACT_DENIED", it.id)
                    setState(it.id, AlarmState.CANCELLED)
                }
            }
        }
        // グレース幅内の過去分: PENDING に戻して即時トリガ (サービス経由で鳴らす)
        for (fire in plan.toFireNow) {
            db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(fire)))
            if (!scheduler.schedule(fire.copy(triggerAtMillis = Clock.System.now().toEpochMilliseconds()))) {
                audit("EXACT_DENIED", fire.id)
            }
        }
        audit(
            "RESYNC",
            "reason=$reason +${plan.toSchedule.size} -${plan.toCancel.size} fire=${plan.toFireNow.size}",
        )
        appScope.launch { pruneOldLogs() }
        onScheduleChanged?.invoke()
    }

    /** 単発指定で MISSED 化する。鳴動を試みたが起動を OS に拒否された時などに使う。 */
    suspend fun markMissed(id: String, detail: String? = null) {
        setState(id, AlarmState.MISSED)
        audit("MISS", if (detail == null) id else "$id: $detail")
        onScheduleChanged?.invoke()
    }

    /** 鳴動時刻が過去の PENDING 行を MISSED に整理する（健全性チェック用）。 */
    suspend fun markMissed() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        db.scheduledInstances().pending()
            .filter { it.triggerAtMillis < cutoff }
            .forEach {
                setState(it.id, AlarmState.MISSED)
                audit("MISS", it.id)
            }
    }

    // ---- 設定更新 ----

    suspend fun upsertStandaloneAlarm(alarm: StandaloneAlarm) {
        val entity = alarm.toEntity()
        val id = if (entity.id == 0L) db.standaloneAlarms().upsert(entity) else {
            db.standaloneAlarms().upsert(entity); entity.id
        }
        // 予約は再同期で整理する
        resync("alarm:$id updated")
    }

    suspend fun deleteStandaloneAlarm(id: Long) {
        db.standaloneAlarms().delete(id)
        resync("alarm:$id deleted")
    }

    suspend fun setCalendarPref(
        calendarId: String,
        enabled: Boolean,
        minutesBefore: Int,
        allDayMinutes: Int? = null,
        extraOffsets: List<Int>? = null,
    ) {
        val current = db.calendarPrefs().all().firstOrNull { it.calendarId == calendarId }
        db.calendarPrefs().upsert(
            CalendarPrefEntity(
                calendarId = calendarId,
                enabled = enabled,
                minutesBefore = minutesBefore,
                allDayMinutes = allDayMinutes ?: current?.allDayMinutes ?: 540,
                extraOffsetsCsv = extraOffsets?.toCsv() ?: current?.extraOffsetsCsv ?: "",
            ),
        )
        resync("calendar pref")
    }

    suspend fun setEventOverride(
        instanceKey: String,
        muted: Boolean?,
        minutesBefore: Int?,
        extraOffsets: List<Int>? = null,
    ) {
        val current = db.eventOverrides().all().firstOrNull { it.instanceKey == instanceKey }
        val m = muted ?: current?.muted ?: false
        val mb = minutesBefore ?: current?.minutesBefore
        val csv = extraOffsets?.toCsv() ?: current?.extraOffsetsCsv
        db.eventOverrides().upsert(EventOverrideEntity(instanceKey, m, mb, csv))
        resync("event override")
    }

    suspend fun removeEventOverride(instanceKey: String) {
        db.eventOverrides().delete(instanceKey)
        resync("event override cleared")
    }

    /** 終了済みインスタンス行を7日で掃除する。 */
    suspend fun pruneOldInstances() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - 7L * 24 * 60 * 60 * 1000
        db.scheduledInstances().pending()
            .filter { it.triggerAtMillis < cutoff }
            .forEach { setState(it.id, AlarmState.MISSED) }
    }

    private suspend fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        db.auditLog().prune(cutoff)
    }

    // ---- 変換 ----

    private fun StandaloneAlarmEntity.toModel() = StandaloneAlarm(
        id = id,
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysOfWeek = maskToDays(daysMask),
        label = label,
        soundUri = soundUri,
        snoozeMinutes = snoozeMinutes,
        exceptions = exceptionsCsv.split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            .toSet(),
    )

    private fun StandaloneAlarm.toEntity() = StandaloneAlarmEntity(
        id = id,
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysMask = daysToMask(daysOfWeek),
        label = label,
        soundUri = soundUri,
        snoozeMinutes = snoozeMinutes,
        exceptionsCsv = exceptions.joinToString(",") { it.toString() },
    )

    companion object {
        // ビット: 1=SUN,2=MON,4=TUE,8=WED,16=THU,32=FRI,64=SAT
        fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d ->
            acc or (1 shl ((d.ordinal + 1) % 7))
        }

        fun maskToDays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries
            .filter { mask and (1 shl ((it.ordinal + 1) % 7)) != 0 }
            .toSet()
    }
}
