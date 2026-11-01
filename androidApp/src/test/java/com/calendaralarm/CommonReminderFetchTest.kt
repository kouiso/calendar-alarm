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
 * 全イベント共通の追加リマインダー (#143)。開始が予約の上限 (14日) より先の予定でも、
 * 前倒しで鳴動が14日以内に入るなら予約されることを、取得範囲を守る端末カレンダーで確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommonReminderFetchTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private val day = 86_400_000L
    private val start = Clock.System.now().toEpochMilliseconds() + 20 * day

    /** Instances の取得範囲 (/instances/when/<begin>/<end>) に入る時だけ予定1件を返す */
    private class RangeProvider : ContentProvider() {
        companion object { var eventStart = 0L }
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val seg = uri.pathSegments
            val row: Map<String, Any?>? = when {
                seg.contains("instances") -> {
                    val i = seg.indexOf("when")
                    val begin = seg[i + 1].toLong()
                    val end = seg[i + 2].toLong()
                    if (eventStart in begin..end) mapOf(
                        CalendarContract.Instances.EVENT_ID to 1L,
                        CalendarContract.Instances.CALENDAR_ID to 1L,
                        CalendarContract.Instances.TITLE to "遠い予定",
                        CalendarContract.Instances.DESCRIPTION to "",
                        CalendarContract.Instances.EVENT_LOCATION to "",
                        CalendarContract.Instances.BEGIN to eventStart,
                        CalendarContract.Instances.END to eventStart + 3_600_000L,
                        CalendarContract.Instances.ALL_DAY to 0,
                        CalendarContract.Instances.SELF_ATTENDEE_STATUS to CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
                        CalendarContract.Instances.ORGANIZER to null,
                        CalendarContract.Instances.AVAILABILITY to CalendarContract.Events.AVAILABILITY_BUSY,
                    ) else null
                }
                seg.contains("calendars") -> mapOf(
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
        RangeProvider.eventStart = start
        Robolectric.buildContentProvider(RangeProvider::class.java).create(CalendarContract.AUTHORITY)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = settings,
            hasCalendarPermission = { true },
            appScope = CoroutineScope(Dispatchers.IO),
        )
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private suspend fun pendingOffsets(): Set<Int> =
        db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("ev:") }
            .map { it.id.substringAfterLast(":b").toInt() }
            .toSet()

    @Test
    fun `20日後の予定は共通リマインダー7日前の分だけ予約される`() = runBlocking {
        settings.setExtraReminderOffsets(listOf(7 * 24 * 60))
        repository.resync("test")
        assertEquals(setOf(7 * 24 * 60), pendingOffsets())
    }

    @Test
    fun `共通リマインダーが無ければ14日より先の予定は予約しない`() = runBlocking {
        repository.resync("test")
        assertEquals(emptySet<Int>(), pendingOffsets())
    }
}
