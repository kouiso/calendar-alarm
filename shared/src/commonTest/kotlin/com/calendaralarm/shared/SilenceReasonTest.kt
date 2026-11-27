package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.SilenceReason
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.Availability
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.shared.model.TitleCodeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 予定一覧の鳴らない行の理由 (#332)。理由の有無は鳴らない判定と一致し、2つ以上当てはまる時は強い方を返す */
class SilenceReasonTest {

    private val event = CalendarEvent(
        id = "e", calendarId = "c1", title = "会議",
        startMillis = 1_800_000_000_000L, endMillis = 1_800_003_600_000L, allDay = false,
    )

    private fun reason(
        event: CalendarEvent = this.event,
        rule: AlarmRule = AlarmRule(),
        override: EventOverride? = null,
        codes: TitleCodeSettings = TitleCodeSettings(),
        invite: InviteFilter = InviteFilter(),
        types: EventTypeFilter = EventTypeFilter(),
        hasReminderTriggers: Boolean = false,
        muteAll: Boolean = false,
        calendarOff: Boolean = false,
        accountOff: Boolean = false,
    ) = AlarmExpander.silenceReason(
        event, rule, override, codes, invite, types, hasReminderTriggers, muteAll, calendarOff, accountOff,
    )

    @Test
    fun `鳴る予定は理由なし`() {
        assertNull(reason())
    }

    @Test
    fun `カレンダーを鳴らさない`() {
        assertEquals(SilenceReason.CALENDAR_OFF, reason(calendarOff = true))
    }

    @Test
    fun `アカウントを鳴らさない`() {
        assertEquals(SilenceReason.ACCOUNT_OFF, reason(accountOff = true))
    }

    @Test
    fun `終日を鳴らさないカレンダーの終日予定`() {
        assertEquals(
            SilenceReason.ALL_DAY_OFF,
            reason(event = event.copy(allDay = true), rule = AlarmRule(allDayMinutes = -1)),
        )
    }

    @Test
    fun `その予定だけ鳴らさない`() {
        assertEquals(SilenceReason.EVENT_OFF, reason(override = EventOverride(action = EventAction.MUTE)))
    }

    @Test
    fun `招待の返事で外れる`() {
        assertEquals(SilenceReason.INVITE, reason(event = event.copy(inviteStatus = InviteStatus.DECLINED)))
    }

    @Test
    fun `種類で外れる`() {
        assertEquals(
            SilenceReason.EVENT_TYPE,
            reason(event = event.copy(title = "誕生日", allDay = true), types = EventTypeFilter(birthday = false)),
        )
    }

    @Test
    fun `予定なしで外れる`() {
        assertEquals(
            SilenceReason.FREE_TIME,
            reason(event = event.copy(availability = Availability.FREE), types = EventTypeFilter(free = false)),
        )
    }

    @Test
    fun `題の言葉で外れる`() {
        assertEquals(
            SilenceReason.TITLE_CODE,
            reason(
                event = event.copy(title = "休み 会議"),
                codes = TitleCodeSettings(neverCodes = listOf("休み")),
            ),
        )
    }

    @Test
    fun `カレンダーの鳴らし方が鳴らさない`() {
        assertEquals(
            SilenceReason.CALENDAR_ACTION_OFF,
            reason(rule = AlarmRule(startAction = EventAction.MUTE, reminderAction = EventAction.MUTE)),
        )
    }

    @Test
    fun `題の言葉が当たってもカレンダーが鳴らさないならカレンダーの理由`() {
        assertEquals(
            SilenceReason.CALENDAR_ACTION_OFF,
            reason(
                event = event.copy(title = "休み 会議"),
                rule = AlarmRule(startAction = EventAction.MUTE, reminderAction = EventAction.MUTE),
                codes = TitleCodeSettings(neverCodes = listOf("休み")),
            ),
        )
    }

    @Test
    fun `すべてミュート中のアラーム`() {
        assertEquals(SilenceReason.MUTE_ALL, reason(muteAll = true))
    }

    @Test
    fun `すべてミュート中でも通知は鳴る`() {
        assertNull(reason(rule = AlarmRule(startAction = EventAction.NOTIFY), muteAll = true))
    }

    @Test
    fun `題の言葉とすべてミュートが両方当たる時は題の言葉`() {
        assertEquals(
            SilenceReason.TITLE_CODE,
            reason(
                event = event.copy(title = "休み 会議"),
                codes = TitleCodeSettings(neverCodes = listOf("休み")),
                muteAll = true,
            ),
        )
    }

    @Test
    fun `鳴らすの言葉で上げた予定がすべてミュートで止まる時はすべてミュート`() {
        assertEquals(
            SilenceReason.MUTE_ALL,
            reason(
                event = event.copy(title = "-w 会議"),
                rule = AlarmRule(startAction = EventAction.MUTE, reminderAction = EventAction.MUTE),
                codes = TitleCodeSettings(alwaysCodes = listOf("-w")),
                muteAll = true,
            ),
        )
    }

    @Test
    fun `2つ当てはまる時は強い方`() {
        assertEquals(
            SilenceReason.CALENDAR_OFF,
            reason(event = event.copy(inviteStatus = InviteStatus.DECLINED), calendarOff = true),
        )
        assertEquals(
            SilenceReason.ACCOUNT_OFF,
            reason(override = EventOverride(action = EventAction.MUTE), accountOff = true),
        )
    }

    @Test
    fun `アラームを指定した予定は絞り込みを通る`() {
        assertNull(
            reason(
                event = event.copy(inviteStatus = InviteStatus.DECLINED),
                override = EventOverride(action = EventAction.ALARM),
            ),
        )
        // すべてミュート中なら、鳴らないのはミュートのため
        assertEquals(
            SilenceReason.MUTE_ALL,
            reason(
                event = event.copy(inviteStatus = InviteStatus.DECLINED),
                override = EventOverride(action = EventAction.ALARM),
                muteAll = true,
            ),
        )
    }

    @Test
    fun `理由の有無は何も鳴らない判定と一致する`() {
        val invites = listOf<InviteStatus?>(null, InviteStatus.ACCEPTED, InviteStatus.NEEDS_ACTION, InviteStatus.DECLINED)
        val codeSets = listOf(
            "会議" to TitleCodeSettings(),
            "休み 会議" to TitleCodeSettings(neverCodes = listOf("休み")),
        )
        val actions = listOf(EventAction.ALARM, EventAction.NOTIFY, EventAction.MUTE)
        for (invite in invites) for ((title, codes) in codeSets) for (start in actions) for (reminderAct in actions) {
            for (hasReminder in listOf(false, true)) for (muteAll in listOf(false, true)) {
                val ev = event.copy(title = title, inviteStatus = invite)
                val rule = AlarmRule(startAction = start, reminderAction = reminderAct)
                val filter = InviteFilter()
                val types = EventTypeFilter()
                val actual = AlarmExpander.silenceReason(
                    ev, rule, null, codes, filter, types, hasReminder, muteAll, calendarOff = false, accountOff = false,
                )
                // 展開と同じ判定から出した「何も鳴らない」
                val silent = if (!AlarmExpander.passesEventFilters(ev, null, filter, types)) {
                    true
                } else {
                    val (s, r) = AlarmExpander.resolveActions(ev, rule, null, codes)
                    fun EventAction.off() = this == EventAction.MUTE || (muteAll && this == EventAction.ALARM)
                    s.off() && (r.off() || !hasReminder)
                }
                assertEquals(
                    silent, actual != null,
                    "invite=$invite title=$title start=$start reminder=$reminderAct hasReminder=$hasReminder muteAll=$muteAll",
                )
            }
        }
    }
}
