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
import com.calendaralarm.data.db.ScheduledInstanceEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 設定で「何分前」「スヌーズ」の既定を変えたら、resync を呼ばなくても予約済みのアラームが新しい分数になる (#231 #254)。
 * 画面と同じく SettingsRepository の setter だけを呼ぶ。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleDefaultsResyncTest {

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

    // DataStore は同じ JVM の他の試験クラスと共有されるので、既定の分数を残さない
    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** 予約中のこの予定のアラーム */
    private suspend fun pending(): List<ScheduledInstanceEntity> =
        db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("ev:1:1:$start:") }

    private fun waitUntil(timeoutMs: Long, cond: suspend () -> Boolean): Boolean = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return@runBlocking true
            Thread.sleep(50)
        }
        cond()
    }

    @Test
    fun `何分前の既定を変えると、resync を呼ばなくても予約の時刻が新しい分数になる`() {
        runBlocking { repository.resync("start") }
        val before = runBlocking { pending() }
        assertEquals("最初は開始時に鳴る", listOf(start), before.map { it.triggerAtMillis })
        repository.watchScheduleDefaults(scope)
        // 見張りが最初の値を受け取ってから変える (最初の値では再同期しない)
        Thread.sleep(300)

        runBlocking { settings.setDefaultMinutesBefore(15) }

        assertTrue(
            "15分前に予約し直されること",
            waitUntil(5_000) { pending().map { it.triggerAtMillis } == listOf(start - 15 * 60_000L) },
        )
    }

    @Test
    fun `スヌーズの既定を変えると、resync を呼ばなくても予約のスヌーズ分数が新しい値になる`() {
        runBlocking { repository.resync("start") }
        assertEquals(listOf(10), runBlocking { pending() }.map { it.snoozeMinutes })
        repository.watchScheduleDefaults(scope)
        Thread.sleep(300)

        runBlocking { settings.setDefaultSnoozeMinutes(5) }

        assertTrue(
            "スヌーズ5分で予約し直されること",
            waitUntil(5_000) { pending().map { it.snoozeMinutes } == listOf(5) },
        )
    }

    @Test
    fun `見張る既定は何分前とスヌーズの2つ`() {
        // 予約に効く欄を足した時に、ここを直し忘れないための固定 (他の欄は今は画面が resync を呼ぶ)
        assertEquals(
            listOf("minutesBefore", "snoozeMinutes"),
            AlarmRepository.ScheduleDefaults::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name },
        )
    }
}
