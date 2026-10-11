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
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    fun `未予約の過去evインスタンスはMISSEDが保存され次回resyncで除外される`() = runBlocking {
        // applyPlan の不意打ち防止と同じ手順: setState は行が無いと更新0なので
        // 先に行を作ってから終端化する。保存されないと次回 resync で再展開される。
        val inst = AlarmInstance(
            "ev:c1:e1:1000:b30",
            Clock.System.now().toEpochMilliseconds() - 30 * 60_000,
            "予定", AlarmKind.EVENT,
        )
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(inst)))
        repository.markMissed(inst.id)
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(inst.id)?.state)
        // 終端行が残る限り同じ入力の次回 resync でも蘇生しない
        repository.resync("test")
        repository.resync("test")
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(inst.id)!!.state)
    }

    @Test
    fun `未予約の過去evインスタンスは実resyncでMISSED化され2回目で再処理されない`() = runBlocking {
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
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(pastId)?.state)
        // 開始分だけが予約され、過去の通知分は鳴動予約を持たない
        val startId = "ev:1:1:$start:b0"
        assertEquals(AlarmState.PENDING.name, db.scheduledInstances().byId(startId)?.state)
        assertEquals(1, scheduledCount())
        // 同じ入力で2回呼んでも終端行が残るため再処理されない
        repositoryWithCalendar.resync("test")
        repositoryWithCalendar.resync("test")
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId(pastId)!!.state)
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
}
