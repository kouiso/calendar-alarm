package com.calendaralarm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 放置の自動スヌーズと手動の停止が重なった時 (#145)。どちらの順で届いても停止を優先し、
 * 止めたはずのアラームがスヌーズの子として後から鳴らないことを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoSnoozeRaceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 鳴動中 (FIRED) の行を 1 つ作る */
    private suspend fun ringing(id: String): AlarmInstance {
        val inst = AlarmInstance(
            id = id, triggerAtMillis = System.currentTimeMillis() - 1000,
            title = "t", kind = AlarmKind.TIMER, snoozeMinutes = 10,
        )
        repository.scheduleAdhoc(inst)
        repository.setState(id, AlarmState.FIRED)
        return inst
    }

    private suspend fun state(id: String) = db.scheduledInstances().byId(id)?.state

    private suspend fun children(id: String) =
        db.scheduledInstances().all().filter { it.id.startsWith("$id:snz") }

    @Test
    fun `放置のまま時間切れなら自動スヌーズの子を予約する`() = runBlocking {
        val inst = ringing("tm:auto")
        assertTrue(repository.onAutoSnoozed(inst.id, 10))
        assertEquals(AlarmState.SNOOZED.name, state(inst.id))
        assertEquals(listOf(AlarmState.PENDING.name), children(inst.id).map { it.state })
    }

    @Test
    fun `先に停止されていたら自動スヌーズは何もしない`() = runBlocking {
        val inst = ringing("tm:stop-first")
        repository.onDismissed(inst.id)
        assertFalse(repository.onAutoSnoozed(inst.id, 10))
        assertEquals(AlarmState.DISMISSED.name, state(inst.id))
        assertTrue(children(inst.id).isEmpty())
    }

    @Test
    fun `自動スヌーズの直後に停止が届いたら子も取り消して鳴らさない`() = runBlocking {
        val inst = ringing("tm:snooze-first")
        assertTrue(repository.onAutoSnoozed(inst.id, 10))
        repository.onDismissed(inst.id)
        assertEquals(AlarmState.DISMISSED.name, state(inst.id))
        assertEquals(listOf(AlarmState.CANCELLED.name), children(inst.id).map { it.state })
    }

    @Test
    fun `手動スヌーズの子は親への停止で取り消さない`() = runBlocking {
        // 自動スヌーズ以外 (利用者が選んだスヌーズ) は今までどおり残す
        val inst = ringing("tm:manual")
        repository.onSnoozed(inst.id, 10)
        repository.onDismissed(inst.id)
        assertEquals(listOf(AlarmState.PENDING.name), children(inst.id).map { it.state })
    }

    @Test
    fun `先に停止されていたら時間切れでも見逃しにしない`() = runBlocking {
        val inst = ringing("tm:miss-race")
        repository.onDismissed(inst.id)
        assertFalse(repository.markMissedIfFired(inst.id, "時間切れ"))
        assertEquals(AlarmState.DISMISSED.name, state(inst.id))
    }

    @Test
    fun `鳴動中のまま時間切れなら見逃しにする`() = runBlocking {
        val inst = ringing("tm:miss")
        assertTrue(repository.markMissedIfFired(inst.id, "時間切れ"))
        assertEquals(AlarmState.MISSED.name, state(inst.id))
    }

    @Test
    fun `停止の後に遅れて届いた手動スヌーズは子を作らない`() = runBlocking {
        val inst = ringing("tm:late-snooze")
        repository.onDismissed(inst.id)
        repository.onSnoozed(inst.id, 10)
        assertEquals(AlarmState.DISMISSED.name, state(inst.id))
        assertTrue(children(inst.id).isEmpty())
    }

    @Test
    fun `自動スヌーズから30秒を過ぎて届いた停止は子を取り消さない`() = runBlocking {
        var now = 1_000_000L
        repository.nowMillis = { now }
        val inst = ringing("tm:late-stop")
        assertTrue(repository.onAutoSnoozed(inst.id, 10))
        now += AlarmRepository.AUTO_SNOOZE_RACE_MS + 1
        repository.onDismissed(inst.id)
        // 「同時に押した」とはみなさず、予約済みの子はそのまま鳴る
        assertEquals(listOf(AlarmState.PENDING.name), children(inst.id).map { it.state })
    }
}
