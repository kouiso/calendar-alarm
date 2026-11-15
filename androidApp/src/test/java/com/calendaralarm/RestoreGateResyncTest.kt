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
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 復元直後の削除 (#269) が済むまで、どの入口の resync も行を読まない。
 * 先に読むと、復元で運ばれてきた古い指定を付け替えて書き戻し (印は消費済みなので残り続ける)、
 * 期限切れの古い予約を「鳴らせなかった」と通知してしまう。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreGateResyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private val now = Clock.System.now().toEpochMilliseconds()
    private val start = now + 3 * 3_600_000L
    /** 復元元の端末で、この予定がまだ動く前の開始 */
    private val oldStart = start - 2 * 3_600_000L
    private val staleRowId = "ev:9:9:${now - 3_600_000L}:b0"

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
        // アプリ本体の起動 (onCreate) が走らせる後始末が印を見終わるまで待つ。
        // 待たないと、この試験が置いた印をアプリ本体の後始末が先に消費しうる
        val app = ApplicationProvider.getApplicationContext<CalendarAlarmApp>()
        runBlocking { withTimeout(10_000) { app.container.restoreGate.await() } }
        RestoreMarker.consume(context)
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        OneEventProvider.eventStart = start
        Robolectric.buildContentProvider(OneEventProvider::class.java).create(CalendarContract.AUTHORITY)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // DataStore は同じ JVM の他の試験クラスと共有されるので、設定を残さない
    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        RestoreMarker.consume(context)
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private fun newRepository(gate: Deferred<Unit>, onMissed: suspend (Int) -> Unit) = AlarmRepository(
        db = db,
        scheduler = AlarmScheduler(context),
        calendarReader = CalendarContractReader(context),
        settings = settings,
        hasCalendarPermission = { true },
        appScope = scope,
        onMissed = onMissed,
        restoreGate = gate,
    )

    @Test
    fun `門が閉じている間の resync は行を読まず、削除の後に読むので古い指定を書き戻さない`() = runBlocking {
        // 復元で運ばれてきた古い行: 期限切れの予約と、予定が動く前の開始に付いた指定
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity(
                    id = staleRowId, triggerAtMillis = now - 3_600_000L, title = "古い会議", kind = "EVENT", eventId = "9",
                    standaloneAlarmId = null, snoozeMinutes = 5, soundUri = null, minutesBefore = 0,
                    eventStartMillis = now - 3_600_000L, snoozeSeq = 0, state = AlarmState.PENDING.name,
                ),
            ),
        )
        db.eventOverrides().upsert(EventOverrideEntity(instanceKey = "1:1:$oldStart", action = null, minutesBefore = 30))
        RestoreMarker.mark(context)

        // 先に起きた方を記録する。門は await されて初めて動くので、resync が門で待ったことを時間に頼らず知れる
        val first = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val gate = scope.async(start = CoroutineStart.LAZY) {
            first.complete("gate")
            release.await()
        }
        val missed = AtomicInteger()
        val reads = AtomicInteger()
        val repository = newRepository(gate) { n -> missed.addAndGet(n) }
        repository.afterResyncReadForTest = {
            reads.incrementAndGet()
            first.complete("read")
        }

        // 前のプロセスから残った SyncWorker のように、後始末より先に始まった resync
        val leftover = scope.launch { repository.resync("left-over worker") }
        assertEquals("resync は行を読む前に門で待つ", "gate", withTimeout(10_000) { first.await() })
        assertEquals("門が閉じている間は行を読まない", 0, reads.get())

        val cleared = RestoreReset.runIfRestored(context, db, onCleared = { release.complete(Unit) }) {
            repository.resync("restored from backup")
        }
        withTimeout(10_000) { leftover.join() }

        assertTrue(cleared)
        assertEquals("残っていた resync も後始末の resync も、削除の後に読み終わる", 2, reads.get())
        assertEquals("古い指定を付け替えて書き戻さない", emptyList<String>(), db.eventOverrides().all().map { it.instanceKey })
        assertNull("古い予約の行は戻らない", db.scheduledInstances().byId(staleRowId))
        assertEquals("消した古い予約を「鳴らせなかった」と通知しない", 0, missed.get())
    }

    @Test
    fun `アプリの入れ物はリポジトリを作る時に門を渡し、起動で門を開ける`() {
        val app = ApplicationProvider.getApplicationContext<CalendarAlarmApp>()
        // onCreate より先に resync が始まっても待つよう、門はリポジトリを作る時点で付いている
        assertSame(app.container.restoreGate, app.container.repository.restoreGate)
        // onCreate が後始末を始め、印が無い起動でも門を開ける (開かないと以後の resync が全部止まる)
        runBlocking { withTimeout(10_000) { app.container.restoreGate.await() } }
        assertTrue(app.container.restoreGate.isCompleted)
    }
}
