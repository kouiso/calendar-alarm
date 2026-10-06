package com.calendaralarm

import android.app.AlarmManager
import android.content.Context
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

        val states = repository
        // 元は SNOOZED
        // DB の全行を確認
        val all = mutableListOf<com.calendaralarm.data.db.ScheduledInstanceEntity>()
        db.scheduledInstances().allFlow().first().let { all.addAll(it) }
        val orig = all.first { it.id == inst.id }
        assertEquals(AlarmState.SNOOZED.name, orig.state)
        // スヌーズ分が PENDING でスケジュール済み
        val snoozed = all.firstOrNull { it.id.startsWith("${inst.id}#snz") }
        assertNotNull(snoozed)
        assertEquals(AlarmState.PENDING.name, snoozed!!.state)
        assertTrue(snoozed.triggerAtMillis > System.currentTimeMillis())
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
