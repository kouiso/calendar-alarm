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
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * 再同期 (resync) の判定を shared の ScheduleReconciler へ移す前後で動きが変わらないことの固定 (#304)。
 * 移す前の AlarmRepository で通し、移した後も本文を変えずに通す。
 * これまでテストが無かった枝 (繰り返しを止めた後・すべてミュート・権限なし・予定のスヌーズの子・
 * 同じ id の時刻変更・読み取りの後にできた行) を押さえる。作りは ScheduleDefaultsResyncTest と同じ。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResyncCharacterizationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

    /** カレンダーの権限。false にすると予定は読まない (リポジトリは作り直さない) */
    private var permitted = true

    /** 端末カレンダーに予定を1件だけ置く。開始・終日・読み取りの失敗を試験から切り替える */
    private class ScriptedEventProvider : ContentProvider() {
        companion object {
            var eventStart = 0L
            var allDay = false
            var fail = false
        }
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val path = uri.path.orEmpty()
            if (fail && path.contains("instances")) throw IllegalStateException("scripted failure")
            val row: Map<String, Any?>? = when {
                path.contains("instances") -> mapOf(
                    CalendarContract.Instances.EVENT_ID to 1L,
                    CalendarContract.Instances.CALENDAR_ID to 1L,
                    CalendarContract.Instances.TITLE to "会議",
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
        permitted = true
        ScriptedEventProvider.eventStart = start
        ScriptedEventProvider.allDay = false
        ScriptedEventProvider.fail = false
        Robolectric.buildContentProvider(ScriptedEventProvider::class.java).create(CalendarContract.AUTHORITY)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = settings,
            hasCalendarPermission = { permitted },
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

    /** 今から [hours] 時間後の、現地の時と分 */
    private fun hourMinuteAfter(hours: Int): Pair<Int, Int> {
        val t = (Clock.System.now() + hours.hours).toLocalDateTime(TimeZone.currentSystemDefault())
        return t.hour to t.minute
    }

    /** 全部の曜日に鳴るアラーム (今から [hours] 時間後の時刻) */
    private fun everyDayAlarm(hours: Int): StandaloneAlarm {
        val (h, m) = hourMinuteAfter(hours)
        return StandaloneAlarm(hour = h, minute = m, daysOfWeek = DayOfWeek.entries.toSet())
    }

    private suspend fun state(id: String): String? = db.scheduledInstances().byId(id)?.state

    private suspend fun idsStartingWith(prefix: String): List<String> =
        db.scheduledInstances().all().map { it.id }.filter { it.startsWith(prefix) }

    /** 予定 (1:1:開始) の開始時のアラームの行 */
    private val eventRowId get() = "ev:1:1:$start:b0"

    @Test
    fun `繰り返しのアラームを止めた後の再同期は、止めた回を予約し直さず、他の回は予約したまま`() = runBlocking {
        permitted = false
        repository.upsertStandaloneAlarm(everyDayAlarm(2))
        val before = db.scheduledInstances().pending()
        assertTrue("繰り返しは複数の回を予約する", before.size > 1)
        val first = before.minBy { it.triggerAtMillis }.id
        repository.onFired(first)
        repository.onDismissed(first)

        repository.resync("after dismiss")

        assertEquals(AlarmState.DISMISSED.name, state(first))
        assertEquals(before.map { it.id }.toSet(), db.scheduledInstances().all().map { it.id }.toSet())
        assertEquals(
            (before.map { it.id } - first).toSet(),
            db.scheduledInstances().pending().map { it.id }.toSet(),
        )
    }

    @Test
    fun `すべてミュートにして再同期すると、アラームの予約は取り消され、タイマーは残る`() = runBlocking {
        permitted = false
        repository.upsertStandaloneAlarm(everyDayAlarm(2))
        repository.scheduleAdhoc(
            AlarmInstance(
                id = "tm:x",
                triggerAtMillis = (Clock.System.now() + 30.minutes).toEpochMilliseconds(),
                title = "タイマー",
                kind = AlarmKind.TIMER,
            ),
        )
        assertTrue(idsStartingWith("sa:").isNotEmpty())

        settings.setMuteAll(true)
        repository.resync("mute all")

        val alarmIds = idsStartingWith("sa:")
        assertTrue(alarmIds.isNotEmpty())
        alarmIds.forEach { assertEquals(it, AlarmState.CANCELLED.name, state(it)) }
        assertEquals(AlarmState.PENDING.name, state("tm:x"))
    }

    @Test
    fun `カレンダーの権限が無い再同期は、予定の予約を取り消し、アラームの予約は残す`() = runBlocking {
        repository.resync("with permission")
        assertEquals(AlarmState.PENDING.name, state(eventRowId))
        repository.upsertStandaloneAlarm(everyDayAlarm(2))
        val alarmIds = idsStartingWith("sa:")
        assertTrue(alarmIds.isNotEmpty())

        permitted = false
        repository.resync("without permission")

        assertEquals(AlarmState.CANCELLED.name, state(eventRowId))
        alarmIds.forEach { assertEquals(it, AlarmState.PENDING.name, state(it)) }
    }

    /** 予定を予約し、親を SNOOZED にして、予定のスヌーズの子を予約する。子の id を返す */
    private suspend fun prepareEventSnoozeChild(): String {
        repository.resync("start")
        assertEquals(AlarmState.PENDING.name, state(eventRowId))
        repository.setState(eventRowId, AlarmState.SNOOZED)
        val childId = "$eventRowId:snz1"
        repository.scheduleAdhoc(
            AlarmInstance(
                id = childId,
                triggerAtMillis = (Clock.System.now() + 10.minutes).toEpochMilliseconds(),
                title = "会議",
                kind = AlarmKind.EVENT,
                eventId = "1:1:$start",
                snoozeSeq = 1,
            ),
        )
        return childId
    }

    @Test
    fun `予定のスヌーズの子は、元の予定を読めている間は残り、鳴らさないにすると取り消される`() = runBlocking {
        val childId = prepareEventSnoozeChild()

        repository.resync("child kept")
        assertEquals(AlarmState.PENDING.name, state(childId))

        repository.setEventOverride("1:1:$start", EventAction.MUTE, null)
        assertEquals(AlarmState.CANCELLED.name, state(childId))
    }

    @Test
    fun `予定のスヌーズの子は、カレンダーの読み取りに失敗した再同期では残る`() = runBlocking {
        val childId = prepareEventSnoozeChild()

        ScriptedEventProvider.fail = true
        repository.resync("read failure")

        assertEquals(AlarmState.PENDING.name, state(childId))
    }

    @Test
    fun `同じ id のまま鳴動時刻が変わった予約は、新しい時刻で予約し直す`() = runBlocking {
        // 終日の予定の開始は UTC 0 時。2 日後の UTC 0 時にする
        val begin = (System.currentTimeMillis() / 86_400_000L + 2) * 86_400_000L
        ScriptedEventProvider.eventStart = begin
        ScriptedEventProvider.allDay = true
        val id = "ev:1:1:$begin:b0"

        repository.resync("start")
        val before = db.scheduledInstances().byId(id)
        assertEquals(AlarmState.PENDING.name, before?.state)

        repository.setCalendarPref("1", enabled = true, minutesBefore = null, allDayMinutes = 600)

        val after = db.scheduledInstances().byId(id)
        assertEquals(AlarmState.PENDING.name, after?.state)
        assertEquals(before!!.triggerAtMillis + 3_600_000L, after!!.triggerAtMillis)
    }

    @Test
    fun `読み取りの後にできた予約の行は、その再同期では触らず、次の再同期で判定する`() = runBlocking {
        // 親のアラーム (99) は無い。鳴動時刻が過去だと次の再同期の先頭で MISSED になるので、30 分後にする
        val child = AlarmInstance(
            id = "sa:99:x:snz1",
            triggerAtMillis = (Clock.System.now() + 30.minutes).toEpochMilliseconds(),
            title = "x",
            kind = AlarmKind.STANDALONE,
            snoozeSeq = 1,
        )
        repository.afterResyncReadForTest = {
            repository.afterResyncReadForTest = null
            repository.scheduleAdhoc(child)
        }

        repository.resync("during read")
        assertEquals(AlarmState.PENDING.name, state(child.id))

        repository.resync("next")
        assertEquals(AlarmState.CANCELLED.name, state(child.id))
    }
}
