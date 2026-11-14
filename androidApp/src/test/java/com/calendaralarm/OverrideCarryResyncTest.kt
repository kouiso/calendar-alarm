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
import com.calendaralarm.shared.model.EventAction
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
 * 予定の時刻が動いても、その予定に決めた「何分前」「鳴らさない」が残り、終わった回の指定は消える (#243 #282)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverrideCarryResyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L
    private val moved = start + 2 * 3_600_000L

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
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** 予約中のこの予定のアラームの id */
    private suspend fun pendingIds(): Set<String> =
        db.scheduledInstances().all().filter { it.state == AlarmState.PENDING.name }.map { it.id }.toSet()

    @Test
    fun `時刻が動いた予定も、その予定に決めた何分前で鳴る`() = runBlocking {
        repository.setEventOverride("1:1:$start", null, 30)
        assertEquals(setOf("ev:1:1:$start:b30"), pendingIds())

        // 主催者が予定を 2 時間後へ動かした
        OneEventProvider.eventStart = moved
        repository.resync("event moved")

        assertEquals(setOf("ev:1:1:$moved:b30"), pendingIds())
        assertEquals(listOf("1:1:$moved"), db.eventOverrides().all().map { it.instanceKey })
    }

    @Test
    fun `時刻が動いた予定も、鳴らさないのまま`() = runBlocking {
        repository.setEventOverride("1:1:$start", EventAction.MUTE, null)
        assertEquals(emptySet<String>(), pendingIds())

        OneEventProvider.eventStart = moved
        repository.resync("event moved")

        assertEquals(emptySet<String>(), pendingIds())
        assertEquals(EventAction.MUTE.name, db.eventOverrides().all().single { it.instanceKey == "1:1:$moved" }.action)
    }

    @Test
    fun `終わった回の指定は再同期で消える`() = runBlocking {
        val past = Clock.System.now().toEpochMilliseconds() - 3 * 24 * 3_600_000L
        db.eventOverrides().upsert(com.calendaralarm.data.db.EventOverrideEntity("1:2:$past", EventAction.MUTE.name, null))
        repository.resync("prune")
        assertEquals(emptyList<String>(), db.eventOverrides().all().map { it.instanceKey })
    }
}
