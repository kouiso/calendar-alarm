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
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * カレンダーの行番号が変わっても、設定が同じカレンダーに付く (#268)。
 * 端末カレンダーの提供元を差し替えて、行番号の入れ替わり・復元・画面からの変更との競合を通しで確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarPrefRebindResyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

    /** 提供元のカレンダー 1 件分。予定は行番号と同じ番号の予定を 1 件ずつ持つ */
    data class Cal(val id: Long, val name: String, val syncId: String)

    private class SwappableProvider : ContentProvider() {
        companion object {
            var eventStart = 0L
            var calendars: List<Cal> = emptyList()

            /** true の間、鍵の材料の読み取り (ACCOUNT_TYPE を含む問い合わせ) だけ失敗させる */
            var failIdentities = false
        }
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val path = uri.path.orEmpty()
            if (failIdentities && projection?.contains(CalendarContract.Calendars.ACCOUNT_TYPE) == true) {
                throw IllegalStateException("identity read failed")
            }
            val rows: List<Map<String, Any?>> = when {
                path.contains("instances") -> calendars.map {
                    mapOf(
                        CalendarContract.Instances.EVENT_ID to it.id,
                        CalendarContract.Instances.CALENDAR_ID to it.id,
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
                }
                path.contains("calendars") -> calendars.map {
                    mapOf(
                        CalendarContract.Calendars._ID to it.id,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to it.name,
                        CalendarContract.Calendars.ACCOUNT_NAME to "work@example.com",
                        CalendarContract.Calendars.ACCOUNT_TYPE to "com.google",
                        CalendarContract.Calendars._SYNC_ID to it.syncId,
                        CalendarContract.Calendars.CALENDAR_COLOR to 0,
                        CalendarContract.Calendars.IS_PRIMARY to 1,
                        CalendarContract.Calendars.VISIBLE to 1,
                    )
                }
                else -> emptyList()
            }
            val cols = (projection ?: emptyArray()).map { it }.toTypedArray()
            val c = MatrixCursor(cols)
            rows.forEach { r -> c.addRow(cols.map { r[it] }.toTypedArray()) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val work1 = Cal(1, "仕事", "work")
    private val family2 = Cal(2, "家族", "family")

    /** 行番号が入れ替わった後の端末: 1 が家族、2 が仕事 */
    private val swapped = listOf(Cal(1, "家族", "family"), Cal(2, "仕事", "work"))

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
        SwappableProvider.eventStart = start
        SwappableProvider.failIdentities = false
        SwappableProvider.calendars = listOf(work1, family2)
        Robolectric.buildContentProvider(SwappableProvider::class.java).create(CalendarContract.AUTHORITY)
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
        RestoreMarker.consume(context)
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** 予約中の予定アラームの行番号 (id は ev:<行番号>:<予定>:<開始>:bN) */
    private suspend fun pendingCalendarIds(): Set<String> =
        db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("ev:") }
            .map { it.id.split(":")[1] }
            .toSet()

    @Test
    fun `行番号が入れ替わった後の再同期で、鳴らさない設定が同じカレンダーに付く`() = runBlocking {
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)
        assertEquals("設定前の「仕事」(行番号 1) は鳴らさない", setOf("2"), pendingCalendarIds())

        SwappableProvider.calendars = swapped
        repository.resync("row ids swapped")

        assertEquals("鳴らさないのは「仕事」(今は行番号 2)。「家族」(行番号 1) が鳴る", setOf("1"), pendingCalendarIds())
    }

    @Test
    fun `復元の後始末を通した後も、鳴らさない設定が同じカレンダーに付く`() = runBlocking {
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)
        RestoreMarker.mark(context)
        RestoreReset.runIfRestored(context, db) { }

        SwappableProvider.calendars = swapped
        repository.resync("restored on another device")

        assertEquals(setOf("1"), pendingCalendarIds())
        val rows = db.calendarPrefs().all()
        assertEquals(1, rows.size)
        assertEquals("2", rows[0].calendarId)
        assertFalse(rows[0].enabled)
    }

    @Test
    fun `setCalendarPref は鍵で保存し、旧形式の行を置き換える`() = runBlocking {
        db.calendarPrefs().upsert(CalendarPrefEntity("1", enabled = true, minutesBefore = 5))
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)

        val rows = db.calendarPrefs().all()
        assertEquals(1, rows.size)
        assertFalse(rows[0].calendarKey.startsWith("id:"))
        assertEquals("1", rows[0].calendarId)
        assertFalse(rows[0].enabled)
    }

    @Test
    fun `結び付けの書き直しは、読んだ後に画面から変えた設定を上書きしない`() = runBlocking {
        SwappableProvider.calendars = listOf(work1)
        db.calendarPrefs().upsert(CalendarPrefEntity("1", enabled = true, minutesBefore = 5))
        repository.afterPrefBindForTest = {
            // 結び付けの読み取りの後、書き直しの前に利用者が設定画面で同じカレンダーを変えた
            db.calendarPrefs().upsert(CalendarPrefEntity("1", enabled = false, minutesBefore = null))
        }
        repository.resync("race with settings screen")

        val legacy = db.calendarPrefs().byKey("id:1")
        assertTrue("利用者の変更の行が残る", legacy != null)
        assertFalse("利用者の変更が古い値で上書きされない", legacy!!.enabled)
    }

    @Test
    fun `設定画面への一覧は今の行番号で返る`() = runBlocking {
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)
        SwappableProvider.calendars = swapped

        val rows = repository.calendarPrefsFlowList()

        assertEquals(1, rows.size)
        assertEquals("2", rows[0].calendarId)
    }

    @Test
    fun `鍵の材料を読めない再同期は、前回の予定の予約を残し、単発アラームは予約する`() = runBlocking {
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)
        assertEquals(setOf("2"), pendingCalendarIds())

        // 行番号が入れ替わり、新しいカレンダー 3 も増えた端末で、鍵の材料だけ読めない
        SwappableProvider.calendars = swapped + Cal(3, "新規", "new")
        SwappableProvider.failIdentities = true
        repository.upsertStandaloneAlarm(StandaloneAlarm(enabled = true, hour = 7, minute = 0, label = "朝"))

        assertEquals("控えの行番号で設定を引かず、予定の予約はそのまま", setOf("2"), pendingCalendarIds())
        val standalone = db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("sa:") }
        assertTrue("単発アラームは予約される", standalone.isNotEmpty())

        SwappableProvider.failIdentities = false
        repository.resync("identities readable again")
        assertEquals("読めた後は鍵で引く: 鳴らさないのは「仕事」(行番号 2)", setOf("1", "3"), pendingCalendarIds())
    }

    @Test
    fun `鍵の材料を読めなくても、予定の一覧と設定の保存は落ちない`() = runBlocking {
        SwappableProvider.failIdentities = true

        assertTrue(repository.upcomingEvents().isEmpty())
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)

        val rows = db.calendarPrefs().all()
        assertEquals(1, rows.size)
        assertEquals("id:1", rows[0].calendarKey)
    }

    @Test
    fun `鍵が同じ 2 つのカレンダーの片方だけ変えても、もう片方に効かない`() = runBlocking {
        SwappableProvider.calendars = listOf(Cal(1, "予定", "dup"), Cal(2, "予定", "dup"))
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)

        assertEquals("1 だけ鳴らさない", setOf("2"), pendingCalendarIds())
        repository.resync("again")
        assertEquals("次の再同期でも 2 は鳴る", setOf("2"), pendingCalendarIds())
        assertEquals("共有の鍵へは移さない", "id:1", db.calendarPrefs().all().single().calendarKey)
    }
}
