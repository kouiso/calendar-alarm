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
import com.calendaralarm.shared.model.EventType
import kotlinx.coroutines.flow.first
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
 * #201: 種類を ON から OFF に切り替えて再同期すると、その種類の予約済みアラームが取り消され、
 * ON に戻すと予約し直される。保存した設定は新しい SettingsRepository (再起動相当) でも読める。
 * 現在の挙動を固定する試験で、挙動を変える試験ではない。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventTypeToggleResyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

    /** 題と終日かどうかを差し替えられる、予定1件だけの端末カレンダー */
    private class OneEventProvider : ContentProvider() {
        companion object {
            var eventStart = 0L
            var title = ""
            var allDay = false
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
                    CalendarContract.Instances.TITLE to title,
                    CalendarContract.Instances.DESCRIPTION to "",
                    CalendarContract.Instances.EVENT_LOCATION to "",
                    CalendarContract.Instances.BEGIN to eventStart,
                    CalendarContract.Instances.END to eventStart + 3_600_000L,
                    CalendarContract.Instances.ALL_DAY to if (allDay) 1 else 0,
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
        OneEventProvider.allDay = false
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

    private suspend fun rows() = db.scheduledInstances().all().filter { it.kind == "EVENT" }

    private suspend fun pendingIds(): Set<String> =
        rows().filter { it.state == AlarmState.PENDING.name }.map { it.id }.toSet()

    private val threeTypes = listOf(
        EventType.ABSENCE to "不在",
        EventType.WORKPLACE to "出社",
        EventType.BIRTHDAY to "誕生日",
    )

    @Test
    fun `既定では3種類の予定は予約されない(現在の挙動を固定)`() = runBlocking {
        for ((_, title) in threeTypes) {
            OneEventProvider.title = title
            repository.resync("default $title")
            assertEquals("$title", emptySet<String>(), pendingIds())
        }
    }

    @Test
    fun `種類をONにして再同期すると予約され、OFFに戻して再同期すると予約済みのものが取り消される(現在の挙動を固定)`() = runBlocking {
        for ((type, title) in threeTypes) {
            OneEventProvider.title = title
            settings.toggleEventType(type)
            repository.resync("on $title")
            val ids = pendingIds()
            assertEquals("$title ON", 1, ids.size)

            settings.toggleEventType(type)
            repository.resync("off $title")
            assertEquals("$title OFF", emptySet<String>(), pendingIds())
            assertEquals(
                "$title: 取り消した行は CANCELLED で残る",
                setOf(AlarmState.CANCELLED.name),
                rows().filter { it.id in ids }.map { it.state }.toSet(),
            )

            // もう一度 ON にすると予約し直される
            settings.toggleEventType(type)
            repository.resync("on again $title")
            assertEquals("$title 再ON", ids, pendingIds())
            settings.toggleEventType(type) // 次の種類のために OFF へ戻す
            repository.resync("reset $title")
        }
    }

    @Test
    fun `終日の予定も種類をOFFにして再同期すると取り消される(現在の挙動を固定)`() = runBlocking {
        OneEventProvider.allDay = true
        // 終日の予定の開始は UTC の0時。2日後の分にして、当日9時が過去にならないようにする
        OneEventProvider.eventStart = (start / 86_400_000L + 2) * 86_400_000L
        OneEventProvider.title = "不在"
        settings.toggleEventType(EventType.ABSENCE)
        repository.resync("on")
        assertEquals(1, pendingIds().size)
        settings.toggleEventType(EventType.ABSENCE)
        repository.resync("off")
        assertEquals(emptySet<String>(), pendingIds())
    }

    @Test
    fun `OFFの選択は新しい SettingsRepository で読み直しても保たれる(現在の挙動を固定)`() = runBlocking {
        settings.toggleEventType(EventType.WORKPLACE)
        assertEquals(true, settings.flow.first().eventTypeFilter.workplace)
        settings.toggleEventType(EventType.WORKPLACE)
        val reopened = SettingsRepository(context).flow.first().eventTypeFilter
        assertEquals(false, reopened.workplace)
        assertEquals(false, reopened.birthday)
        assertEquals(false, reopened.absence)
    }
}
