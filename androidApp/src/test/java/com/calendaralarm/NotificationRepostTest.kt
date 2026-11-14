package com.calendaralarm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知アラーム (`nf:`) の出し直し (#371)。同じ通知キーの再投稿が、止めた直後・鳴動中・
 * スヌーズ待ちの行を PENDING に戻して鳴らし直さないことを、鎖 (nf:X と nf:X:snz*) の状態ごとに確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRepostTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository
    private var now = 1_800_000_000_000L
    private val minute = 60_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { false },
            appScope = CoroutineScope(Dispatchers.IO),
        )
        repository.nowMillis = { now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun inst(id: String, title: String, triggerAt: Long = now) =
        AlarmInstance(id = id, triggerAtMillis = triggerAt, title = title, kind = AlarmKind.NOTIFICATION)

    /** 指定の状態の行を直接置く (経路を通さず、状態だけを作る) */
    private suspend fun seed(id: String, state: AlarmState, title: String = "old", triggerAt: Long = now - 20 * minute) {
        db.scheduledInstances().upsert(listOf(ScheduledInstanceEntity.of(inst(id, title, triggerAt))))
        db.scheduledInstances().setState(id, state.name)
    }

    /** 利用者が止めた時刻の記録 (DISMISS 行) を直接置く */
    private suspend fun seedDismissAudit(id: String, atMillis: Long) {
        db.auditLog().insert(
            AuditLogEntity(atMillis = atMillis, action = "DISMISS", detail = id, instanceId = id, triggerAtMillis = null, title = "old"),
        )
    }

    private suspend fun row(id: String) = db.scheduledInstances().byId(id)

    private suspend fun skipLogs() = db.auditLog().recentByActionsFlow(listOf("NF_REPOST_SKIP"), 50).first()

    private suspend fun post(id: String, title: String = "new") =
        repository.scheduleNotificationAlarm(inst(id, title))

    @Test
    fun `1 PENDING の行は何も書かず予約し直さない`() = runBlocking {
        seed("nf:a", AlarmState.PENDING, triggerAt = now + 5 * minute)
        post("nf:a")
        val r = row("nf:a")!!
        assertEquals("old", r.title)
        assertEquals(now + 5 * minute, r.triggerAtMillis)
        assertEquals(AlarmState.PENDING.name, r.state)
    }

    @Test
    fun `1 鳴動中 FIRED の行を PENDING に戻さない`() = runBlocking {
        seed("nf:b", AlarmState.FIRED)
        post("nf:b")
        val r = row("nf:b")!!
        assertEquals(AlarmState.FIRED.name, r.state)
        assertEquals("old", r.title)
    }

    @Test
    fun `1 スヌーズ待ちの子がある SNOOZED の親は鳴らし直さない`() = runBlocking {
        seed("nf:c", AlarmState.SNOOZED)
        seed("nf:c:snz1", AlarmState.PENDING, triggerAt = now + 5 * minute)
        post("nf:c")
        assertEquals(AlarmState.SNOOZED.name, row("nf:c")!!.state)
        assertEquals(AlarmState.PENDING.name, row("nf:c:snz1")!!.state)
        assertEquals(now + 5 * minute, row("nf:c:snz1")!!.triggerAtMillis)
    }

    @Test
    fun `2 止めて10分未満なら鳴らさず記録を1行書く`() = runBlocking {
        seed("nf:d", AlarmState.DISMISSED)
        seedDismissAudit("nf:d", now - 3 * minute)
        post("nf:d")
        assertEquals(AlarmState.DISMISSED.name, row("nf:d")!!.state)
        assertEquals("old", row("nf:d")!!.title)
        assertEquals(1, skipLogs().size)
    }

    @Test
    fun `2 止めて10分ちょうど以上なら新しいアラームとして鳴らす`() = runBlocking {
        seed("nf:e", AlarmState.DISMISSED)
        seedDismissAudit("nf:e", now - AlarmRepository.NOTIFICATION_REPOST_WINDOW_MS)
        post("nf:e")
        val r = row("nf:e")!!
        assertEquals(AlarmState.PENDING.name, r.state)
        assertEquals("new", r.title)
        assertTrue(skipLogs().isEmpty())
    }

    @Test
    fun `2 落とした出し直しは窓を延ばさない`() = runBlocking {
        seed("nf:f", AlarmState.DISMISSED)
        seedDismissAudit("nf:f", now - 9 * minute)
        post("nf:f") // 9 分: 落とす
        assertEquals(AlarmState.DISMISSED.name, row("nf:f")!!.state)
        now += 2 * minute // 止めてから 11 分
        post("nf:f")
        assertEquals(AlarmState.PENDING.name, row("nf:f")!!.state)
    }

    @Test
    fun `2 スヌーズの子だけが止められていても窓で落とす`() = runBlocking {
        seed("nf:g", AlarmState.SNOOZED)
        seed("nf:g:snz1", AlarmState.DISMISSED)
        seedDismissAudit("nf:g:snz1", now - 2 * minute)
        post("nf:g")
        assertEquals(AlarmState.SNOOZED.name, row("nf:g")!!.state)
        assertEquals(1, skipLogs().size)
    }

    @Test
    fun `2 止めた記録が読めない時は鳴動時刻から数える`() = runBlocking {
        seed("nf:h", AlarmState.DISMISSED, triggerAt = now - 4 * minute)
        seed("nf:i", AlarmState.DISMISSED, triggerAt = now - 12 * minute)
        post("nf:h")
        post("nf:i")
        assertEquals(AlarmState.DISMISSED.name, row("nf:h")!!.state)
        assertEquals(AlarmState.PENDING.name, row("nf:i")!!.state)
    }

    @Test
    fun `2 実際の停止の経路で書いた記録でも直後の出し直しを落とす`() = runBlocking {
        now = System.currentTimeMillis()
        seed("nf:j", AlarmState.FIRED)
        repository.onDismissed("nf:j")
        post("nf:j")
        assertEquals(AlarmState.DISMISSED.name, row("nf:j")!!.state)
        assertEquals("old", row("nf:j")!!.title)
    }

    @Test
    fun `3 行が無ければ今までどおり鳴らす`() = runBlocking {
        post("nf:k")
        val r = row("nf:k")!!
        assertEquals(AlarmState.PENDING.name, r.state)
        assertEquals("new", r.title)
    }

    @Test
    fun `3 MISSED や子の無い SNOOZED は今までどおり鳴らす`() = runBlocking {
        seed("nf:l", AlarmState.MISSED)
        seed("nf:m", AlarmState.SNOOZED)
        seed("nf:n", AlarmState.CANCELLED)
        listOf("nf:l", "nf:m", "nf:n").forEach { post(it) }
        listOf("nf:l", "nf:m", "nf:n").forEach {
            assertEquals(it, AlarmState.PENDING.name, row(it)!!.state)
            assertEquals(it, "new", row(it)!!.title)
        }
    }

    @Test
    fun `別の鎖の行や前方一致だけの id は鎖に入れない`() = runBlocking {
        seed("nf:ab", AlarmState.PENDING)
        post("nf:a")
        assertEquals(AlarmState.PENDING.name, row("nf:a")!!.state)
        assertEquals("new", row("nf:a")!!.title)
    }
}
