package com.calendaralarm

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 再同期が全行を読んだ後、書き戻す前に鳴動・停止・スヌーズが挟まっても、その行を PENDING に戻さない (#232 #257)。
 * 差込口 [AlarmRepository.afterResyncReadForTest] で、読み取りの直後に状態を変える。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResyncTransitionRaceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

    /** カレンダー1件と、その予定1件だけを返す端末カレンダー */
    private class OneEventProvider : ContentProvider() {
        companion object {
            var eventStart = 0L
        }
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val path = uri.path.orEmpty()
            val row: Map<String, Any?>? = when {
                path.contains("instances") -> mapOf(
                    CalendarContract.Instances.EVENT_ID to 1L,
                    CalendarContract.Instances.CALENDAR_ID to 1L,
                    CalendarContract.Instances.TITLE to "会議",
                    CalendarContract.Instances.DESCRIPTION to "",
                    CalendarContract.Instances.EVENT_LOCATION to "",
                    CalendarContract.Instances.BEGIN to eventStart,
                    CalendarContract.Instances.END to eventStart + 3_600_000L,
                    CalendarContract.Instances.ALL_DAY to 0,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS to CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
                    CalendarContract.Instances.ORGANIZER to null,
                    CalendarContract.Instances.AVAILABILITY to CalendarContract.Events.AVAILABILITY_BUSY,
                )
                path.contains("calendars") -> mapOf(
                    CalendarContract.Calendars._ID to 1L,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "仕事",
                    CalendarContract.Calendars.ACCOUNT_NAME to "work@example.com",
                    CalendarContract.Calendars.CALENDAR_COLOR to 0,
                    CalendarContract.Calendars.IS_PRIMARY to 1,
                    CalendarContract.Calendars.VISIBLE to 1,
                )
                else -> null
            }
            val cols = (projection ?: emptyArray()).map { it }.toTypedArray()
            val c = MatrixCursor(cols)
            row?.let { r -> c.addRow(cols.map { r[it] }.toTypedArray()) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        OneEventProvider.eventStart = start
        Robolectric.buildContentProvider(OneEventProvider::class.java).create(CalendarContract.AUTHORITY)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = settings,
            hasCalendarPermission = { true },
            appScope = scope,
        )
    }

    // DataStore は同じ JVM の他の試験クラスと共有されるので、設定を残さない
    @After
    fun tearDown() {
        repository.afterResyncReadForTest = null
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** この予定の開始時のアラームの行 */
    private val rowId get() = "ev:1:1:$start:b0"

    private suspend fun state(id: String): String? = db.scheduledInstances().byId(id)?.state

    @Test
    fun `読み取りの後に鳴って止めた行は、再同期の後も止まったまま`() = runBlocking {
        repository.resync("start")
        assertEquals(AlarmState.PENDING.name, state(rowId))
        repository.afterResyncReadForTest = {
            repository.afterResyncReadForTest = null
            repository.onFired(rowId)
            repository.onDismissed(rowId)
        }
        repository.resync("during ring")
        assertEquals(AlarmState.DISMISSED.name, state(rowId))
        // 次の再同期でも止まったまま (PENDING に戻って「見逃し」や再鳴動にならない)
        repository.resync("next")
        assertEquals(AlarmState.DISMISSED.name, state(rowId))
    }

    @Test
    fun `読み取りの後に鳴ってスヌーズした行は、再同期の後もスヌーズ中で、子の予約が残る`() = runBlocking {
        repository.resync("start")
        repository.afterResyncReadForTest = {
            repository.afterResyncReadForTest = null
            repository.onFired(rowId)
            repository.onSnoozed(rowId, 5)
        }
        repository.resync("during ring")
        assertEquals(AlarmState.SNOOZED.name, state(rowId))
        val children = db.scheduledInstances().all().filter { it.id.startsWith("$rowId:snz") }
        assertEquals(1, children.size)
        assertEquals(AlarmState.PENDING.name, children.single().state)
        repository.resync("next")
        assertEquals(AlarmState.SNOOZED.name, state(rowId))
        assertEquals(AlarmState.PENDING.name, state(children.single().id))
    }

    @Test
    fun `読み取りの後に鳴り始めた行は、再同期の後も鳴動中のまま`() = runBlocking {
        repository.resync("start")
        repository.afterResyncReadForTest = {
            repository.afterResyncReadForTest = null
            repository.onFired(rowId)
        }
        repository.resync("during ring")
        assertEquals(AlarmState.FIRED.name, state(rowId))
    }

    @Test
    fun `読み取りの後に停止で取り消されたスヌーズの子は、再同期で予約し直さない`() = runBlocking {
        repository.resync("start")
        repository.onFired(rowId)
        repository.onSnoozed(rowId, 5)
        val child = db.scheduledInstances().all().single { it.id.startsWith("$rowId:snz") }.id
        assertEquals(AlarmState.PENDING.name, state(child))
        // 停止と自動スヌーズが重なった時、停止がスヌーズの子を取り消す (dismissRow) のと同じ状態にする
        repository.afterResyncReadForTest = {
            repository.afterResyncReadForTest = null
            repository.setState(child, AlarmState.CANCELLED)
        }
        repository.resync("during stop")
        assertEquals(AlarmState.CANCELLED.name, state(child))
    }
}
