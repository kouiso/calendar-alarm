package com.calendaralarm.shared.bridge

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.AlarmPlanner
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.StandaloneAlarm
import com.calendaralarm.shared.model.TitleCodeSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * iOS (Swift) から共有ドメインを呼ぶための JSON ブリッジ。
 * Kotlin/Native の ObjC エクスポートでは kotlinx 型・Map/List の受け渡しが煩雑なため、
 * 出入力をすべて JSON 文字列に揃えて primitive のみの境界にする。
 */
object SharedBridge {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- expand ----

    @Serializable
    data class ExpandRequest(
        val events: List<CalendarEvent> = emptyList(),
        val standalone: List<StandaloneAlarm> = emptyList(),
        val calendarRules: Map<String, AlarmRule> = emptyMap(),
        val overrides: Map<String, EventOverride> = emptyMap(),
        val disabledCalendarIds: Set<String> = emptySet(),
        val nowMillis: Long,
        val horizonMillis: Long,
        val zoneId: String,
        /** グローバル設定の「N分前」。calendar_prefs が無いカレンダーの既定。 */
        val defaultMinutesBefore: Int = 0,
        /** グローバル既定の開始時アクション。 */
        val defaultStartAction: EventAction = EventAction.ALARM,
        /** グローバル既定のリマインダーアクション。 */
        val defaultReminderAction: EventAction = EventAction.ALARM,
        /** 展開されたイベントインスタンスに載せるスヌーズ既定 (分)。 */
        val defaultSnoozeMinutes: Int = 10,
        /** 単発アラームの展開日数。 */
        val standaloneDays: Int = 14,
        /** タイトルコード設定 (always/never + 適用スコープ)。 */
        val titleCodes: TitleCodeSettings = TitleCodeSettings(),
        /** 招待予定フィルタ。 */
        val inviteFilter: InviteFilter = InviteFilter(),
        /** イベント種別フィルタ。 */
        val eventTypeFilter: EventTypeFilter = EventTypeFilter(),
        /** 予定側リマインダーを展開に含めるか。 */
        val importEventReminders: Boolean = false,
        /** 一括ミュート: ALARM 鳴動を全て抑止 (NOTIFYとタイマーは残す)。 */
        val muteAll: Boolean = false,
    )

    @Serializable
    data class ExpandResult(
        val instances: List<AlarmInstance>,
    )

    /**
     * イベント + 単発アラームを AlarmInstance に展開する。
     * Android 側 (AlarmRepository.resync) と同じ規則:
     * - 単発/曜日アラームは [standaloneDays] 日分
     * - イベントはカレンダールールと個別上書きを適用
     * - イベント由来インスタンスの snoozeMinutes はグローバル既定で上書き
     */
    fun expand(requestJson: String): String {
        val req = json.decodeFromString(ExpandRequest.serializer(), requestJson)
        val now = Instant.fromEpochMilliseconds(req.nowMillis)
        val horizon = Instant.fromEpochMilliseconds(req.horizonMillis)
        val zone = TimeZone.of(req.zoneId)

        val desired = mutableListOf<AlarmInstance>()
        desired += req.standalone.flatMap {
            AlarmExpander.expandStandalone(it, now = now, days = req.standaloneDays, zone = zone)
        }
        val expanded = AlarmExpander.expandEvents(
            events = req.events,
            calendarRules = req.calendarRules,
            overrides = req.overrides,
            disabledCalendarIds = req.disabledCalendarIds,
            now = now,
            horizon = horizon,
            zone = zone,
            defaultRule = AlarmRule(
                minutesBefore = req.defaultMinutesBefore,
                startAction = req.defaultStartAction,
                reminderAction = req.defaultReminderAction,
            ),
            titleCodes = req.titleCodes,
            inviteFilter = req.inviteFilter,
            eventTypeFilter = req.eventTypeFilter,
            importEventReminders = req.importEventReminders,
            muteAll = req.muteAll,
        )
        desired += expanded.map { it.copy(snoozeMinutes = req.defaultSnoozeMinutes) }
        // 一括ミュート: イベント側は expandEvents 内で ALARM を落としている。
        // 単発アラームは全て ALARM 鳴動なのでここでまとめて除外 (タイマーは別経路で残る)。
        if (req.muteAll) desired.removeAll { it.kind == AlarmKind.STANDALONE }
        return json.encodeToString(ExpandResult.serializer(), ExpandResult(desired))
    }

    // ---- plan ----

    @Serializable
    data class PlanRequest(
        val desired: List<AlarmInstance>,
        /** 現行予約: instanceId -> triggerAtMillis (PENDING のみ)。 */
        val scheduled: Map<String, Long> = emptyMap(),
        val nowMillis: Long,
    )

    @Serializable
    data class PlanResult(
        val toSchedule: List<AlarmInstance>,
        val toCancel: List<String>,
        val toFireNow: List<AlarmInstance>,
    )

    fun plan(requestJson: String): String {
        val req = json.decodeFromString(PlanRequest.serializer(), requestJson)
        val p = AlarmPlanner.plan(
            scheduled = req.scheduled,
            desired = req.desired,
            now = Instant.fromEpochMilliseconds(req.nowMillis),
        )
        return json.encodeToString(
            PlanResult.serializer(),
            PlanResult(p.toSchedule, p.toCancel, p.toFireNow),
        )
    }
}
