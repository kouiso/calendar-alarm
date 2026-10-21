package com.calendaralarm

import android.app.AlarmManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scheduler: AlarmScheduler
    private lateinit var repository: AlarmRepository
    private lateinit var alarmManager: AlarmManager

    /** カレンダー権限あり＋reader が実際に予定を返す repository (fixture 用に差し替え) */
    private lateinit var repositoryWithCalendar: AlarmRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        scheduler = AlarmScheduler(context)
        repository = AlarmRepository(
            db = db,
            scheduler = scheduler,
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { false }, // 単発アラームのみ展開
            appScope = CoroutineScope(Dispatchers.IO),
        )
        repositoryWithCalendar = AlarmRepository(
            db = db,
            scheduler = scheduler,
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { true },
            appScope = CoroutineScope(Dispatchers.IO),
        )
    }

    /** CalendarContract の fake provider。URI path で Instances/Reminders/Events/Calendars を分岐。 */
    private class FakeCalendarProvider : ContentProvider() {
        companion object {
            var instanceRows: List<Map<String, Any?>> = emptyList()
            var reminderRows: List<Map<String, Any?>> = emptyList()
        }
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val rows = when {
                uri.path?.contains("instances") == true -> instanceRows
                uri.path?.contains("reminders") == true -> reminderRows
                else -> emptyList()
            }
            val cols = projection ?: emptyArray()
            val c = MatrixCursor(cols as Array<String>)
            rows.forEach { r -> c.addRow(cols.map { r[it] }.toTypedArray()) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @After
    fun tearDown() = db.close()

    private fun scheduledCount() = shadowOf(alarmManager).scheduledAlarms.size

    @Test
    fun `schedule は AlarmManager に正確な鳴動を登録する`() {
        val inst = AlarmInstance(
            id = "tm:1",
            triggerAtMillis = System.currentTimeMillis() + 60_000,
            title = "タイマー",
            kind = AlarmKind.TIMER,
        )
        scheduler.schedule(inst)
        assertEquals(1, scheduledCount())
        // 同じ id で予約し直しても増えない (PendingIntent が同一)
        scheduler.schedule(inst.copy(triggerAtMillis = System.currentTimeMillis() + 120_000))
        assertEquals(1, scheduledCount())
    }

    @Test
    fun `未予約の過去evインスタンスはSKIPPEDが保存され次回resyncで除外される`() = runBlocking {
        // applyPlan の不意打ち防止と同じ手順: setState は行が無いと更新0なので
        // 先に行を作ってから終端化する。保存されないと次回 resync で再展開される。
        val inst = AlarmInstance(
            "ev:c1:e1:1000:b30",
            Clock.System.now().toEpochMilliseconds() - 30 * 60_000,
            "予定", AlarmKind.EVENT,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(inst)))
        repository.markSkipped(inst.id, "予約前に通知時刻が過ぎていた")
        assertEquals(AlarmState.SKIPPED.name, db.scheduledInstances().byId(inst.id)?.state)
        // 終端行が残る限り同じ入力の次回 resync でも蘇生しない
        repository.resync("test")
        repository.resync("test")
        assertEquals(AlarmState.SKIPPED.name, db.scheduledInstances().byId(inst.id)!!.state)
    }

    @Test
    fun `未予約の過去evインスタンスは実resyncでSKIPPED化され2回目で再処理されず見逃しに数えない`() = runBlocking {
        // 本番の applyPlan 分岐を通す回帰: reader(fake provider) → expand → plan →
        // applyPlan。production の upsert+MISSED 分岐が無いと、過去分は
        // AlarmManager に予約され DB は PENDING のまま残るので RED になる。
        val start = Clock.System.now().toEpochMilliseconds() + 30 * 60_000
        val inst = mapOf(
            CalendarContract.Instances.EVENT_ID to 1L,
            CalendarContract.Instances.CALENDAR_ID to 1L,
            CalendarContract.Instances.TITLE to "会議",
            CalendarContract.Instances.DESCRIPTION to "",
            CalendarContract.Instances.EVENT_LOCATION to "",
            CalendarContract.Instances.BEGIN to start,
            CalendarContract.Instances.END to start + 3_600_000L,
            CalendarContract.Instances.ALL_DAY to 0,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Instances.ORGANIZER to null,
        )
        // 60分前リマインダー → triggerAt = start-60分 = now-30分 (グレース内の過去)
        val rem = mapOf(
            CalendarContract.Reminders.EVENT_ID to 1L,
            CalendarContract.Reminders.MINUTES to 60,
            CalendarContract.Reminders.METHOD to CalendarContract.Reminders.METHOD_ALERT,
        )
        FakeCalendarProvider.instanceRows = listOf(inst)
        FakeCalendarProvider.reminderRows = listOf(rem)
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        repositoryWithCalendar.resync("test")
        val pastId = "ev:1:1:$start:b60"
        assertEquals(AlarmState.SKIPPED.name, db.scheduledInstances().byId(pastId)?.state)
        // わざと鳴らさなかった分なので「鳴らせなかった」バナーの件数に入らない
        assertEquals(0, repositoryWithCalendar.recentMissedFlow().first())
        // 開始分だけが予約され、過去の通知分は鳴動予約を持たない
        val startId = "ev:1:1:$start:b0"
        assertEquals(AlarmState.PENDING.name, db.scheduledInstances().byId(startId)?.state)
        assertEquals(1, scheduledCount())
        // 同じ入力で2回呼んでも終端行が残るため再処理されない
        repositoryWithCalendar.resync("test")
        repositoryWithCalendar.resync("test")
        assertEquals(AlarmState.SKIPPED.name, db.scheduledInstances().byId(pastId)!!.state)
        assertEquals(1, scheduledCount())
    }

    @Test
    fun `cancel はそのアラームだけを取り消す`() {
        val a = AlarmInstance("a", System.currentTimeMillis() + 60_000, "a", AlarmKind.TIMER)
        val b = AlarmInstance("b", System.currentTimeMillis() + 60_000, "b", AlarmKind.TIMER)
        scheduler.schedule(a)
        scheduler.schedule(b)
        scheduler.cancel("a")
        assertEquals(1, scheduledCount())
    }

    @Test
    fun `単発アラーム登録→resync→AlarmManager に予約される`() = runBlocking {
        val tomorrow = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
            .let { it + kotlinx.datetime.DatePeriod(days = 1) }
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(
                enabled = true, hour = 7, minute = 30,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                label = "朝活",
            ),
        )
        assertTrue(scheduledCount() >= 1)
        val pending = repository.pendingFlow().first()
        assertTrue(pending.all { it.kind == AlarmKind.STANDALONE })
        assertTrue(pending.all { it.title == "朝活" })
    }

    @Test
    fun `スヌーズは元を SNOOZED にして追従インスタンスを予約する`() = runBlocking {
        val inst = AlarmInstance(
            id = "tm:x", triggerAtMillis = System.currentTimeMillis() - 1000,
            title = "t", kind = AlarmKind.TIMER, snoozeMinutes = 10,
        )
        repository.scheduleAdhoc(inst)
        repository.onSnoozed(inst.id)

        // 元は SNOOZED
        val all = db.scheduledInstances().allFlow().first()
        val orig = all.first { it.id == inst.id }
        assertEquals(AlarmState.SNOOZED.name, orig.state)
        // スヌーズ分が PENDING でスケジュール済み
        val snoozed = all.firstOrNull { it.id.startsWith("${inst.id}:snz") }
        assertNotNull(snoozed)
        assertEquals(AlarmState.PENDING.name, snoozed!!.state)
        assertTrue(snoozed.triggerAtMillis > System.currentTimeMillis())

        // resync してもスヌーズ中のタイマー子は生きる (TIMER は無条件持越し)
        repository.resync("test")
        assertEquals(AlarmState.PENDING.name, db.scheduledInstances().byId(snoozed.id)!!.state)
    }

    @Test
    fun `通知アラームの adhoc インスタンスは resync で消えない`() = runBlocking {
        // NOTIFICATION は理想リストに無いが TIMER と同じく保持する。
        // 消えると通知→アラームの発火直前に resync が走った時に無言で鳴らなくなる。
        val inst = AlarmInstance(
            id = "nf:${System.currentTimeMillis()}",
            triggerAtMillis = System.currentTimeMillis() + 30_000,
            title = "通知", kind = AlarmKind.NOTIFICATION,
        )
        repository.scheduleAdhoc(inst)
        repository.resync("test")
        val row = db.scheduledInstances().byId(inst.id)
        assertEquals(AlarmState.PENDING.name, row!!.state)

        // スヌーズ中の通知子も消えない
        repository.onSnoozed(inst.id, 10)
        repository.resync("test")
        val child = db.scheduledInstances().all()
            .firstOrNull { it.id.startsWith("${inst.id}:snz") }
        assertEquals(AlarmState.PENDING.name, child!!.state)
    }

    @Test
    fun `スヌーズ中の子は親アラームが有効ならグレース窓外でも消えない`() = runBlocking {
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(enabled = true, hour = 7, minute = 0, label = "a"),
        )
        val aid = db.standaloneAlarms().all().first().id
        // 親インスタンス (sa:aid:発生日) は鳴動済みでグレース窓外。
        // インスタンス照合だと desired に居らず誤殺される退行防止テスト。
        val child = AlarmInstance(
            id = "sa:$aid:2020-01-01:snz1",
            triggerAtMillis = System.currentTimeMillis() + 30 * 60_000,
            title = "a", kind = AlarmKind.STANDALONE, snoozeSeq = 1,
        )
        repository.scheduleAdhoc(child)
        repository.resync("test")
        val row = db.scheduledInstances().byId(child.id)
        assertEquals(AlarmState.PENDING.name, row!!.state)
    }

    @Test
    fun `親アラームを無効化するとスヌーズ中の子も消える`() = runBlocking {
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(enabled = true, hour = 7, minute = 0),
        )
        val aid = db.standaloneAlarms().all().first().id
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(id = aid, enabled = false, hour = 7, minute = 0),
        )
        val child = AlarmInstance(
            id = "sa:$aid:2020-01-01:snz1",
            triggerAtMillis = System.currentTimeMillis() + 30 * 60_000,
            title = "a", kind = AlarmKind.STANDALONE, snoozeSeq = 1,
        )
        repository.scheduleAdhoc(child)
        repository.resync("test")
        val row = db.scheduledInstances().byId(child.id)
        assertEquals(AlarmState.CANCELLED.name, row!!.state)
    }

    @Test
    fun `単発アラームは一度消費したら二度と鳴らない`() = runBlocking {
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(enabled = true, hour = 7, minute = 0, label = "一回のみ"),
        )
        val aid = db.standaloneAlarms().all().first().id
        // 鳴動→停止で終端に達する
        val inst = AlarmInstance(
            id = "sa:$aid:2026-10-05",
            triggerAtMillis = System.currentTimeMillis() - 1000,
            title = "t", kind = AlarmKind.STANDALONE, standaloneAlarmId = aid,
        )
        repository.scheduleAdhoc(inst)
        repository.onFired(inst.id)
        repository.onDismissed(inst.id)
        repository.resync("test")
        // アラーム本体が OFF になり、翌日分が二度と発行されない
        assertEquals(false, db.standaloneAlarms().byId(aid)!!.enabled)
        assertTrue(repository.pendingFlow().first().none { it.id.startsWith("sa:$aid:") })
    }

    @Test
    fun `消費した単発アラームは再有効化で復活する`() = runBlocking {
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(enabled = true, hour = 7, minute = 0),
        )
        val aid = db.standaloneAlarms().all().first().id
        val inst = AlarmInstance(
            id = "sa:$aid:2026-10-05",
            triggerAtMillis = System.currentTimeMillis() - 1000,
            title = "t", kind = AlarmKind.STANDALONE, standaloneAlarmId = aid,
        )
        repository.scheduleAdhoc(inst)
        repository.onFired(inst.id)
        repository.onDismissed(inst.id)
        assertEquals(false, db.standaloneAlarms().byId(aid)!!.enabled)
        // 再ONは消費のリセット: 翌日分が再発行される
        repository.upsertStandaloneAlarm(
            StandaloneAlarm(id = aid, enabled = true, hour = 7, minute = 0),
        )
        assertTrue(db.standaloneAlarms().byId(aid)!!.enabled)
        assertTrue(repository.pendingFlow().first().any { it.id.startsWith("sa:$aid:") })
    }

    @Test
    fun `鳴動→停止で DISMISSED になり AlarmManager から消える`() = runBlocking {
        val inst = AlarmInstance(
            id = "ev:y", triggerAtMillis = System.currentTimeMillis() + 60_000,
            title = "t", kind = AlarmKind.EVENT,
        )
        repository.scheduleAdhoc(inst)
        repository.onFired(inst.id)
        repository.onDismissed(inst.id)

        val row = db.scheduledInstances().byId(inst.id)
        assertEquals(AlarmState.DISMISSED.name, row!!.state)
        assertEquals(0, scheduledCount())
    }

    @Test
    fun `見逃しの件数は24時間以内に鳴らせなかった予約だけを数える`() = runBlocking {
        val now = System.currentTimeMillis()
        // 予約したのに鳴らせなかった (期限切れ PENDING → MISSED) は数える
        val stale = AlarmInstance("ev:c1:e1:1:b0", now - 2 * 3_600_000L, "会議", AlarmKind.EVENT)
        repository.scheduleAdhoc(stale)
        repository.markMissed()
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(stale.id)!!.state)
        assertEquals(1, repository.recentMissedFlow().first())
        // 24時間より前の見逃しと、わざと鳴らさなかった分 (SKIPPED) は数えない
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity.of(
                    AlarmInstance("ev:c1:e2:2:b0", now - 25 * 3_600_000L, "昨日", AlarmKind.EVENT),
                    AlarmState.MISSED,
                ),
                ScheduledInstanceEntity.of(
                    AlarmInstance("ev:c1:e3:3:b30", now - 3_600_000L, "取込", AlarmKind.EVENT),
                    AlarmState.SKIPPED,
                ),
            ),
        )
        assertEquals(1, repository.recentMissedFlow().first())
        // 窓の端: ちょうど24時間前は数え、それより1ミリ秒でも古ければ数えない
        val edge = stale.triggerAtMillis + 24 * 3_600_000L
        assertEquals(1, repository.recentMissedFlow(nowMillis = { edge }).first())
        assertEquals(0, repository.recentMissedFlow(nowMillis = { edge + 1 }).first())
    }

    @Test
    fun `見逃しの件数は画面を開いたままでも時間が進めば24時間を過ぎた分を外す`() = runBlocking {
        val now = System.currentTimeMillis()
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity.of(
                    AlarmInstance("ev:c1:e9:9:b0", now - 2 * 3_600_000L, "会議", AlarmKind.EVENT),
                    AlarmState.MISSED,
                ),
            ),
        )
        // 1回目は今、2回目は23時間後 (見逃しから25時間後) の時計で数える
        val clocks = ArrayDeque(listOf(now, now + 23 * 3_600_000L))
        var last = now
        val counts = repository.recentMissedFlow(
            nowMillis = { clocks.removeFirstOrNull()?.also { last = it } ?: last },
            refreshMillis = 300L,
        ).take(2).toList()
        assertEquals(listOf(1, 0), counts)
    }

    @Test
    fun `予定一覧のスイッチはカレンダーOFFと終日OFFの予定で押せず、鳴らない側に出る`() = runBlocking {
        val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L
        val allDayStart = (Clock.System.now().toEpochMilliseconds() / 86_400_000L + 1) * 86_400_000L
        fun row(eventId: Long, calId: Long, begin: Long, allDay: Boolean) = mapOf(
            CalendarContract.Instances.EVENT_ID to eventId,
            CalendarContract.Instances.CALENDAR_ID to calId,
            CalendarContract.Instances.TITLE to "予定$eventId",
            CalendarContract.Instances.DESCRIPTION to "",
            CalendarContract.Instances.EVENT_LOCATION to "",
            CalendarContract.Instances.BEGIN to begin,
            CalendarContract.Instances.END to begin + if (allDay) 86_400_000L else 3_600_000L,
            CalendarContract.Instances.ALL_DAY to if (allDay) 1 else 0,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Instances.ORGANIZER to null,
        )
        FakeCalendarProvider.instanceRows = listOf(
            row(1, 1, start, allDay = false),
            row(2, 1, allDayStart, allDay = true),
            row(3, 2, start, allDay = false),
        )
        FakeCalendarProvider.reminderRows = emptyList()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        // カレンダー1は終日を鳴らさない、カレンダー2は丸ごと鳴らさない
        db.calendarPrefs().upsert(CalendarPrefEntity("1", enabled = true, minutesBefore = 0, allDayMinutes = -1))
        db.calendarPrefs().upsert(CalendarPrefEntity("2", enabled = false, minutesBefore = 0))
        // スイッチを一度 OFF→ON した後と同じ: 予定ごとの ALARM 指定があっても鳴らない
        db.eventOverrides().upsert(EventOverrideEntity("1:2:$allDayStart", action = "ALARM", minutesBefore = null))

        val items = repositoryWithCalendar.upcomingEvents().associateBy { it.event.id }
        val timed = items.getValue("1")
        assertEquals(false, timed.muted)
        assertEquals(false, timed.lockedByCalendar)
        val allDay = items.getValue("2")
        assertTrue(allDay.allDayOff)
        assertTrue(allDay.muted)
        assertTrue(allDay.lockedByCalendar)
        val calOff = items.getValue("3")
        assertTrue(calOff.calendarOff)
        assertTrue(calOff.muted)
        assertTrue(calOff.lockedByCalendar)
        // 一覧の表示と実際の予約が一致する: 終日の予定とカレンダー2の予定は予約されない
        repositoryWithCalendar.resync("test")
        val pendingIds = db.scheduledInstances().pending().map { it.id }
        assertEquals(listOf("ev:1:1:$start:b0"), pendingIds)
    }

    @Test
    fun `開始が鳴らずリマインダーの時刻も無い予定は、行のスイッチを鳴らない側に出す`() = runBlocking {
        val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L
        fun row(eventId: Long) = mapOf(
            CalendarContract.Instances.EVENT_ID to eventId,
            CalendarContract.Instances.CALENDAR_ID to 1L,
            CalendarContract.Instances.TITLE to "予定$eventId",
            CalendarContract.Instances.DESCRIPTION to "",
            CalendarContract.Instances.EVENT_LOCATION to "",
            CalendarContract.Instances.BEGIN to start,
            CalendarContract.Instances.END to start + 3_600_000L,
            CalendarContract.Instances.ALL_DAY to 0,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Instances.ORGANIZER to null,
        )
        FakeCalendarProvider.instanceRows = listOf(row(1), row(2))
        // 予定2だけカレンダー側に 10 分前の通知がある
        FakeCalendarProvider.reminderRows = listOf(
            mapOf(
                CalendarContract.Reminders.EVENT_ID to 2L,
                CalendarContract.Reminders.MINUTES to 10,
                CalendarContract.Reminders.METHOD to CalendarContract.Reminders.METHOD_ALERT,
            ),
        )
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        // 開始は鳴らさない、リマインダーは鳴らす。追加の何分前は無し
        db.calendarPrefs().upsert(
            CalendarPrefEntity("1", enabled = true, minutesBefore = 0, startAction = "MUTE", reminderAction = "ALARM"),
        )

        val items = repositoryWithCalendar.upcomingEvents().associateBy { it.event.id }
        // 予定1は鳴る時刻が1つも無いので、スイッチは鳴らない側
        assertTrue(items.getValue("1").muted)
        // 予定2はカレンダーの通知 (10 分前) で鳴るので、スイッチは鳴る側
        assertEquals(false, items.getValue("2").muted)
        // 一覧の表示と実際の予約が一致する
        repositoryWithCalendar.resync("test")
        val pendingIds = db.scheduledInstances().pending().map { it.id }
        assertEquals(listOf("ev:1:2:$start:b10"), pendingIds)
    }

    @Test
    fun `カレンダーの通知がシステム既定 (-1) だけの予定も、鳴るなら行のスイッチを鳴る側に出す`() = runBlocking {
        val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L
        FakeCalendarProvider.instanceRows = listOf(
            mapOf(
                CalendarContract.Instances.EVENT_ID to 2L,
                CalendarContract.Instances.CALENDAR_ID to 1L,
                CalendarContract.Instances.TITLE to "予定2",
                CalendarContract.Instances.DESCRIPTION to "",
                CalendarContract.Instances.EVENT_LOCATION to "",
                CalendarContract.Instances.BEGIN to start,
                CalendarContract.Instances.END to start + 3_600_000L,
                CalendarContract.Instances.ALL_DAY to 0,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                    CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
                CalendarContract.Instances.ORGANIZER to null,
            ),
        )
        // 予約側は -1 を既定分数 (defaultMinutesBefore) に置き換えて鳴らすので、一覧も同じ扱いにする
        FakeCalendarProvider.reminderRows = listOf(
            mapOf(
                CalendarContract.Reminders.EVENT_ID to 2L,
                CalendarContract.Reminders.MINUTES to CalendarContract.Reminders.MINUTES_DEFAULT,
                CalendarContract.Reminders.METHOD to CalendarContract.Reminders.METHOD_ALERT,
            ),
        )
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        db.calendarPrefs().upsert(
            CalendarPrefEntity("1", enabled = true, minutesBefore = 0, startAction = "MUTE", reminderAction = "ALARM"),
        )

        // 既定分数を開始の 0 分と違う値にして、-1 がこの設定に解決されたことを予約の ID で確かめる
        val settings = SettingsRepository(context)
        val before = settings.flow.first().defaultMinutesBefore
        settings.setDefaultMinutesBefore(15)
        try {
            val item = repositoryWithCalendar.upcomingEvents().associateBy { it.event.id }.getValue("2")
            repositoryWithCalendar.resync("test")
            val pendingIds = db.scheduledInstances().pending().map { it.id }
            assertEquals(listOf("ev:1:2:$start:b15"), pendingIds)
            // 予約があるので、スイッチは鳴る側 (鳴らないと出して鳴るのが一番まずい)
            assertEquals(false, item.muted)
            assertTrue(item.hasReminderTriggers)
        } finally {
            // 設定は DataStore に残り他の試験へ漏れるので戻す
            settings.setDefaultMinutesBefore(before)
        }
    }

    @Test
    fun `すべてミュート中はアラームの予定を鳴らない側に出してスイッチを押せなくし、通知の予定は鳴る側に残して切れるようにする`() = runBlocking {
        val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L
        fun row(eventId: Long, calId: Long) = mapOf(
            CalendarContract.Instances.EVENT_ID to eventId,
            CalendarContract.Instances.CALENDAR_ID to calId,
            CalendarContract.Instances.TITLE to "予定$eventId",
            CalendarContract.Instances.DESCRIPTION to "",
            CalendarContract.Instances.EVENT_LOCATION to "",
            CalendarContract.Instances.BEGIN to start,
            CalendarContract.Instances.END to start + 3_600_000L,
            CalendarContract.Instances.ALL_DAY to 0,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Instances.ORGANIZER to null,
        )
        FakeCalendarProvider.instanceRows = listOf(row(1, 1), row(2, 2))
        FakeCalendarProvider.reminderRows = emptyList()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        // カレンダー1はアラーム、カレンダー2は通知だけ
        db.calendarPrefs().upsert(CalendarPrefEntity("1", enabled = true, minutesBefore = 0, startAction = "ALARM"))
        db.calendarPrefs().upsert(CalendarPrefEntity("2", enabled = true, minutesBefore = 0, startAction = "NOTIFY"))
        // スイッチを一度 OFF→ON した後と同じ: 予定ごとの ALARM 指定があってもすべてミュートで外れる
        db.eventOverrides().upsert(EventOverrideEntity("1:1:$start", action = "ALARM", minutesBefore = null))

        val settings = SettingsRepository(context)
        settings.setMuteAll(true)
        try {
            val items = repositoryWithCalendar.upcomingEvents().associateBy { it.event.id }
            val alarm = items.getValue("1")
            assertTrue(alarm.muted)
            assertTrue(alarm.switchLocked)
            val notify = items.getValue("2")
            assertEquals(false, notify.muted)
            // 通知はすべてミュート中も鳴るので、行のスイッチで切れる
            assertEquals(false, notify.switchLocked)
            // 一覧の表示と実際の予約が一致する: 通知の予定だけ予約される
            repositoryWithCalendar.resync("test")
            val pendingIds = db.scheduledInstances().pending().map { it.id }
            assertEquals(listOf("ev:2:2:$start:b0"), pendingIds)
        } finally {
            // 設定は DataStore に残り他の試験へ漏れるので戻す
            settings.setMuteAll(false)
        }
    }

    @Test
    fun `期限切れ PENDING は MISSED 化される`() = runBlocking {
        val stale = AlarmInstance(
            id = "old", triggerAtMillis = System.currentTimeMillis() - 3_600_000 * 2,
            title = "t", kind = AlarmKind.EVENT,
        )
        repository.scheduleAdhoc(stale)
        repository.markMissed()
        val row = db.scheduledInstances().byId(stale.id)
        assertEquals(AlarmState.MISSED.name, row!!.state)
    }

    @Test
    fun `監査ログに鳴動系イベントが残る`() = runBlocking {
        val inst = AlarmInstance(
            id = "audit1", triggerAtMillis = System.currentTimeMillis() + 60_000,
            title = "t", kind = AlarmKind.TIMER,
        )
        repository.scheduleAdhoc(inst)
        repository.onFired(inst.id)
        repository.onDismissed(inst.id)
        val logs = repository.auditFlow(10).first()
        val actions = logs.map { it.action }
        assertTrue("SCHEDULE" in actions)
        assertTrue("FIRE" in actions)
        assertTrue("DISMISS" in actions)
    }

    @Test
    fun `押し出された先行は鳴動待ちに戻り、後発の停止後に鳴らし直しの予約が入る`() = runBlocking {
        val now = System.currentTimeMillis()
        val prev = AlarmInstance(id = "sup-p", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT)
        val next = AlarmInstance(id = "sup-n", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT)
        repository.scheduleAdhoc(prev)
        repository.scheduleAdhoc(next)
        repository.onFired(prev.id)
        repository.onFired(next.id)
        repository.onDisplaced(prev.id, next.id)

        // 押し出された側は誰も止めていないので終端にしない (FIRED のまま待つ)
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(prev.id)!!.state)

        repository.onDismissed(next.id)
        assertEquals(1, repository.rearmWaitingRings(next.id))
        assertEquals("鳴らし直す時は AlarmReceiver が受け付ける PENDING に戻す",
            AlarmState.PENDING.name, db.scheduledInstances().byId(prev.id)!!.state)
        assertEquals("同じ鳴動を二度は予約し直さない", 0, repository.rearmWaitingRings(null))
        val alarm = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).nextScheduledAlarm
        assertTrue("先行の鳴らし直しが今すぐ予約される", alarm != null && alarm.triggerAtMs - System.currentTimeMillis() < 5_000)
        assertTrue(repository.auditFlow(20).first().any { it.action == "REARM" && it.detail.startsWith(prev.id) })
    }

    @Test
    fun `止めた鳴動・通知のみ・猶予切れは鳴らし直さない`() = runBlocking {
        val now = System.currentTimeMillis()
        val stopped = AlarmInstance(id = "rm-stopped", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT)
        val notify = AlarmInstance(
            id = "rm-notify", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT,
            delivery = com.calendaralarm.shared.model.EventAction.NOTIFY,
        )
        val stale = AlarmInstance(id = "rm-stale", triggerAtMillis = now - 2 * 60 * 60_000L, title = "t", kind = AlarmKind.EVENT)
        val future = AlarmInstance(id = "rm-future", triggerAtMillis = now + 60 * 60_000L, title = "t", kind = AlarmKind.EVENT)
        db.scheduledInstances().upsert(listOf(stopped, notify, stale, future).map { ScheduledInstanceEntity.of(it) })
        repository.onFired(stopped.id)
        repository.onDismissed(stopped.id)
        repository.onFired(notify.id)

        assertEquals(0, repository.rearmWaitingRings(resolvedId = null))
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(notify.id)!!.state)
    }


    @Test
    fun `押し出されて待っている間の resync は待ち側を予約し直さない`() = runBlocking {
        val now = System.currentTimeMillis()
        val prev = AlarmInstance(id = "wait-p", triggerAtMillis = now, title = "t", kind = AlarmKind.TIMER)
        repository.scheduleAdhoc(prev)
        repository.onFired(prev.id)
        repository.onDisplaced(prev.id, "wait-n")
        scheduler.cancel(prev.id)
        repository.resync("test")
        // 予約し直すと過去の時刻ですぐ発火し、後発の鳴動中に割り込んでしまう
        val armed = shadowOf(alarmManager).scheduledAlarms
            .mapNotNull { shadowOf(it.operation).savedIntent.data?.lastPathSegment }
        assertTrue("待ち側が AlarmManager に入っていないこと: $armed", prev.id !in armed)
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(prev.id)!!.state)
    }

    @Test
    fun `通知権限なしで鳴動に回った通知のみの予定も、押し出されたら鳴らし直す`() = runBlocking {
        val now = System.currentTimeMillis()
        val notify = AlarmInstance(
            id = "wait-notify", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT,
            delivery = com.calendaralarm.shared.model.EventAction.NOTIFY,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(notify)))
        repository.onFired(notify.id)
        repository.onDisplaced(notify.id, "other")
        assertEquals(1, repository.rearmWaitingRings(resolvedId = "other"))
    }

    @Test
    fun `待つ間に猶予を過ぎた押し出し済みの鳴動は見逃しになり FIRED のまま残らない`() = runBlocking {
        val now = System.currentTimeMillis()
        val late = AlarmInstance(
            id = "wait-expired", triggerAtMillis = now - 61 * 60_000L, title = "t", kind = AlarmKind.EVENT,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(late)))
        repository.onFired(late.id)
        repository.onDisplaced(late.id, "other")

        assertEquals(0, repository.rearmWaitingRings(resolvedId = "other"))
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(late.id)!!.state)
        assertTrue(repository.auditFlow(20).first().any { it.action == "MISS" && it.detail.startsWith(late.id) })
        assertEquals("見逃しにした行は待ち行列から外れる", 0, repository.rearmWaitingRings(resolvedId = null))
    }

    /** OS のカレンダー provider と同じく、権限の無い読み取りを SecurityException で拒む fake。 */
    private class DenyingCalendarProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw SecurityException("requires android.permission.READ_CALENDAR")
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test
    fun `カレンダー権限が無くても設定のカレンダー一覧は空で返り落ちない`() = runBlocking {
        Robolectric.buildContentProvider(DenyingCalendarProvider::class.java).create(CalendarContract.AUTHORITY)
        assertTrue(repository.calendarSources().isEmpty())
    }

    @Test
    fun `権限ありと判定した直後に外されても一覧は空で返り落ちない`() = runBlocking {
        Robolectric.buildContentProvider(DenyingCalendarProvider::class.java).create(CalendarContract.AUTHORITY)
        assertTrue(repositoryWithCalendar.calendarSources().isEmpty())
    }

    @Test
    fun `待つ間に親のアラームを OFF にされたら鳴らし直さない`() = runBlocking {
        val alarmId = db.standaloneAlarms().upsert(
            com.calendaralarm.data.db.StandaloneAlarmEntity(
                hour = 7, minute = 0, enabled = true, daysMask = 0, label = "p",
                soundUri = null, snoozeMinutes = 10, exceptionsCsv = "",
            ),
        )
        val now = System.currentTimeMillis()
        val waitingRow = ScheduledInstanceEntity.of(
            AlarmInstance(id = "wait-off", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT),
        ).copy(standaloneAlarmId = alarmId)
        db.scheduledInstances().upsert(listOf(waitingRow))
        repository.onFired("wait-off")
        repository.onDisplaced("wait-off", "other")
        db.standaloneAlarms().byId(alarmId)!!.let { db.standaloneAlarms().upsert(it.copy(enabled = false)) }

        assertEquals(0, repository.rearmWaitingRings(resolvedId = "other"))
        assertEquals(AlarmState.CANCELLED.name, db.scheduledInstances().byId("wait-off")!!.state)
    }

    @Test
    fun `待つ間に予定をミュートされたら鳴らし直さない・ミュートしなければ待ち続ける`() = runBlocking {
        val start = Clock.System.now().toEpochMilliseconds() + 30 * 60_000
        val inst = mapOf(
            CalendarContract.Instances.EVENT_ID to 1L,
            CalendarContract.Instances.CALENDAR_ID to 1L,
            CalendarContract.Instances.TITLE to "会議",
            CalendarContract.Instances.DESCRIPTION to "",
            CalendarContract.Instances.EVENT_LOCATION to "",
            CalendarContract.Instances.BEGIN to start,
            CalendarContract.Instances.END to start + 3_600_000L,
            CalendarContract.Instances.ALL_DAY to 0,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Instances.ORGANIZER to null,
        )
        FakeCalendarProvider.instanceRows = listOf(inst)
        FakeCalendarProvider.reminderRows = emptyList()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        repositoryWithCalendar.resync("test")
        val id = "ev:1:1:$start:b0"
        repositoryWithCalendar.onFired(id)
        repositoryWithCalendar.onDisplaced(id, "other")

        // 対照: 何も変えない resync では待ち続ける
        repositoryWithCalendar.resync("control")
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(id)!!.state)

        repositoryWithCalendar.setEventOverride("1:1:$start", com.calendaralarm.shared.model.EventAction.MUTE, null)
        assertEquals(AlarmState.CANCELLED.name, db.scheduledInstances().byId(id)!!.state)
        assertEquals(0, repositoryWithCalendar.rearmWaitingRings(resolvedId = "other"))
    }

    @Test
    fun `待つ間に一括ミュートされたら鳴らし直さない`() = runBlocking {
        val now = System.currentTimeMillis()
        val w = AlarmInstance(id = "wait-mute-all", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT)
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(w)))
        repository.onFired(w.id)
        repository.onDisplaced(w.id, "other")
        val settings = SettingsRepository(context)
        settings.setMuteAll(true)
        // 一括ミュートの対象外 (タイマー) は、重なっても鳴らし直す
        val timer = AlarmInstance(id = "wait-timer", triggerAtMillis = now, title = "t", kind = AlarmKind.TIMER)
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(timer)))
        repository.onFired(timer.id)
        repository.onDisplaced(timer.id, "other")
        try {
            assertEquals(1, repository.rearmWaitingRings(resolvedId = "other"))
            assertEquals(AlarmState.CANCELLED.name, db.scheduledInstances().byId(w.id)!!.state)
            assertEquals(AlarmState.PENDING.name, db.scheduledInstances().byId(timer.id)!!.state)
        } finally {
            // 設定は DataStore に残り他の試験へ漏れるので戻す
            settings.setMuteAll(false)
        }
    }

    @Test
    fun `待つ間の resync は猶予切れとスヌーズの子を取り消さず、猶予切れは見逃しにする`() = runBlocking {
        FakeCalendarProvider.instanceRows = emptyList()
        FakeCalendarProvider.reminderRows = emptyList()
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(CalendarContract.AUTHORITY)
        val now = System.currentTimeMillis()
        val expired = AlarmInstance(
            id = "ev:9:9:1:b0", triggerAtMillis = now - 61 * 60_000L, title = "t", kind = AlarmKind.EVENT,
        )
        val snoozeChild = AlarmInstance(
            id = "ev:9:9:2:b0:snz1", triggerAtMillis = now, title = "t", kind = AlarmKind.EVENT, snoozeSeq = 1,
        )
        db.scheduledInstances().upsert(listOf(expired, snoozeChild).map { ScheduledInstanceEntity.of(it) })
        listOf(expired, snoozeChild).forEach {
            repositoryWithCalendar.onFired(it.id)
            repositoryWithCalendar.onDisplaced(it.id, "other")
        }

        repositoryWithCalendar.resync("while-waiting")
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(expired.id)!!.state)
        assertEquals(AlarmState.FIRED.name, db.scheduledInstances().byId(snoozeChild.id)!!.state)

        assertEquals(1, repositoryWithCalendar.rearmWaitingRings(resolvedId = "other"))
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(expired.id)!!.state)
    }

}
