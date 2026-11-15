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
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 行を読んだ後・書く前に停止・スヌーズが挟まっても、その行を MISSED・CANCELLED で上書きしない。
 * 見逃しの件数・通知は、実際に変えた行の数から出す (#366 #232)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedWritesRaceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val missedCounts = mutableListOf<Int>()
    private val farStart = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

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
        OneEventProvider.eventStart = farStart
        Robolectric.buildContentProvider(OneEventProvider::class.java).create(CalendarContract.AUTHORITY)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        missedCounts.clear()
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { true },
            appScope = scope,
            onMissed = { missedCounts += it },
        )
    }

    @After
    fun tearDown() {
        repository.beforeMissedWriteForTest = null
        repository.beforeCancelStaleWriteForTest = null
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private suspend fun state(id: String): String? = db.scheduledInstances().byId(id)?.state

    private suspend fun put(id: String, triggerAt: Long, state: AlarmState = AlarmState.PENDING) {
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity.of(
                    AlarmInstance(id = id, triggerAtMillis = triggerAt, title = "t", kind = AlarmKind.EVENT),
                    state,
                ),
            ),
        )
    }

    private val now get() = System.currentTimeMillis()
    private val overdue get() = now - 2 * 3_600_000L

    @Test
    fun `markMissed(id) は、読んだ後に停止された行を MISSED にせず false を返す`() = runBlocking {
        val id = "ev:1:7:1:b0"
        put(id, overdue)
        repository.beforeMissedWriteForTest = {
            repository.beforeMissedWriteForTest = null
            repository.onDismissed(id)
        }
        assertFalse(repository.markMissed(id, "テスト"))
        assertEquals(AlarmState.DISMISSED.name, state(id))
    }

    @Test
    fun `markMissed(id) は、読んだ後にスヌーズされた行を MISSED にしない`() = runBlocking {
        val id = "ev:1:8:1:b0"
        put(id, now - 60_000L)
        repository.beforeMissedWriteForTest = {
            repository.beforeMissedWriteForTest = null
            repository.onFired(id)
            repository.onSnoozed(id, 5)
        }
        assertFalse(repository.markMissed(id, "テスト"))
        assertEquals(AlarmState.SNOOZED.name, state(id))
    }

    @Test
    fun `markMissed(id) は、停止が無ければ PENDING の行を MISSED にして true を返す`() = runBlocking {
        val id = "ev:1:9:1:b0"
        put(id, overdue)
        assertTrue(repository.markMissed(id, "テスト"))
        assertEquals(AlarmState.MISSED.name, state(id))
    }

    @Test
    fun `再同期の先頭の markMissed() は、読んだ後に停止された行を数えず MISSED にもしない`() = runBlocking {
        val id = "ev:1:10:1:b0"
        put(id, overdue)
        repository.beforeMissedWriteForTest = {
            repository.beforeMissedWriteForTest = null
            repository.onDismissed(id)
        }
        assertEquals(0, repository.markMissed())
        assertEquals(AlarmState.DISMISSED.name, state(id))
    }

    @Test
    fun `再同期は、先頭の読み取りの後に停止された行の見逃し通知を出さない`() = runBlocking {
        val id = "ev:1:11:1:b0"
        put(id, overdue)
        repository.beforeMissedWriteForTest = {
            repository.beforeMissedWriteForTest = null
            repository.onDismissed(id)
        }
        repository.resync("race")
        assertEquals(AlarmState.DISMISSED.name, state(id))
        assertTrue("見逃し通知が出ている: $missedCounts", missedCounts.isEmpty())
    }

    @Test
    fun `停止が無ければ、時刻を過ぎた PENDING の行は MISSED になり見逃し通知はちょうど 1 回`() = runBlocking {
        val id = "ev:1:12:1:b0"
        put(id, overdue)
        repository.resync("overdue")
        assertEquals(AlarmState.MISSED.name, state(id))
        assertEquals(listOf(1), missedCounts)
    }

    private suspend fun waitingEvent(id: String) {
        put(id, now - 5 * 60_000L, AlarmState.FIRED)
        repository.onDisplaced(id, "other")
    }

    @Test
    fun `押し出されて待つ予定の取り消しは、読んだ後に停止された行を CANCELLED にしない`() = runBlocking {
        val id = "ev:1:13:1:b0"
        waitingEvent(id)
        repository.beforeCancelStaleWriteForTest = {
            repository.beforeCancelStaleWriteForTest = null
            repository.onDismissed(id)
        }
        repository.resync("stale")
        assertEquals(AlarmState.DISMISSED.name, state(id))
    }

    @Test
    fun `押し出されて待つ予定は、予定が展開されなくなり停止も無ければ CANCELLED になる`() = runBlocking {
        val id = "ev:1:14:1:b0"
        waitingEvent(id)
        repository.resync("stale")
        assertEquals(AlarmState.CANCELLED.name, state(id))
    }

    @Test
    fun `押し出された鳴動は、停止が無ければ鳴らし直される`() = runBlocking {
        val prev = "ev:1:15:1:b0"
        waitingEvent(prev)
        assertEquals(1, repository.rearmWaitingRings(resolvedId = "other"))
        assertEquals(AlarmState.PENDING.name, state(prev))
    }

    @Test
    fun `rearmWaitingRings から markMissed(id) への入れ子は自分で止まらず、見逃しは 1 回だけ数える`() = runBlocking {
        val id = "ev:1:16:1:b0"
        put(id, now - 61 * 60_000L, AlarmState.FIRED)
        repository.onDisplaced(id, "other")
        val rearmed = withTimeout(5_000) { repository.rearmWaitingRings(resolvedId = "other") }
        assertEquals(0, rearmed)
        assertEquals(AlarmState.MISSED.name, state(id))
        assertEquals(listOf(1), missedCounts)
    }

    @Test
    fun `猶予を過ぎた待ち行でも、読んだ後に停止されていれば見逃しを数えない`() = runBlocking {
        val id = "ev:1:17:1:b0"
        put(id, now - 61 * 60_000L, AlarmState.FIRED)
        repository.onDisplaced(id, "other")
        repository.beforeMissedWriteForTest = {
            repository.beforeMissedWriteForTest = null
            repository.onDismissed(id)
        }
        repository.rearmWaitingRings(resolvedId = "other")
        assertEquals(AlarmState.DISMISSED.name, state(id))
        assertTrue("見逃し通知が出ている: $missedCounts", missedCounts.isEmpty())
    }

    @Test
    fun `markSkipped は、停止済みの行を SKIPPED にしない`() = runBlocking {
        val id = "ev:1:18:1:b0"
        put(id, overdue, AlarmState.DISMISSED)
        assertFalse(repository.markSkipped(id, "テスト"))
        assertEquals(AlarmState.DISMISSED.name, state(id))
    }
}
