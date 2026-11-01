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
 * おやすみモードで鳴らさなかった行の記録 (#149)。鳴動中 (FIRED) の行だけを MISSED にし、
 * 先に止めた行を「鳴らせなかった」で上書きしないことを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DndSuppressedStateTest {

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

    private suspend fun fired(id: String) {
        repository.scheduleAdhoc(
            AlarmInstance(id = id, triggerAtMillis = System.currentTimeMillis() - 1000, title = "t", kind = AlarmKind.TIMER),
        )
        repository.setState(id, AlarmState.FIRED)
    }

    @Test
    fun `鳴動中の行は鳴らせなかったにする`() = runBlocking {
        fired("tm:dnd")
        assertTrue(repository.markDndSuppressed("tm:dnd", "おやすみモード"))
        assertEquals(AlarmState.MISSED.name, db.scheduledInstances().byId("tm:dnd")?.state)
    }

    @Test
    fun `先に止めた行は上書きしない`() = runBlocking {
        fired("tm:dnd-stopped")
        repository.onDismissed("tm:dnd-stopped")
        assertFalse(repository.markDndSuppressed("tm:dnd-stopped", "おやすみモード"))
        assertEquals(AlarmState.DISMISSED.name, db.scheduledInstances().byId("tm:dnd-stopped")?.state)
    }
}
