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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.Migration5To6
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AccountSetting
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * アカウントの「何分前」が配下カレンダーの既定として効き続けることを、設定の保存から予約まで通して確かめる (#74)。
 * カレンダーのスイッチや終日の時刻を触っても、何分前を選ばない限りアカウントの既定に従う。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDefaultMinutesTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

    /** カレンダー1件 (work@example.com) と、その予定1件だけを返す端末カレンダー */
    private class OneAccountProvider : ContentProvider() {
        companion object {
            var eventStart = 0L
            /** false = カレンダー一覧だけ空で返す (一覧の読み取り失敗) */
            var calendarsReadable = true
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
                path.contains("calendars") && calendarsReadable -> mapOf(
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
        OneAccountProvider.eventStart = start
        OneAccountProvider.calendarsReadable = true
        Robolectric.buildContentProvider(OneAccountProvider::class.java).create(CalendarContract.AUTHORITY)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = settings,
            hasCalendarPermission = { true },
            appScope = CoroutineScope(Dispatchers.IO),
        )
    }

    // DataStore は同じ JVM の他の試験クラスと共有されるので、アカウント設定を残さない
    @After
    fun tearDown() {
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** 予約中の予定アラームの「何分前」 (id 末尾の bN) */
    private suspend fun pendingOffsets(): Set<Int> =
        db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("ev:1:1:$start:") }
            .map { it.id.substringAfterLast(":b").toInt() }
            .toSet()

    @Test
    fun `スイッチを切って入れ直したカレンダーも、アカウントを30分前に変えると30分前で鳴る`() = runBlocking {
        settings.setAccountSetting("work@example.com", AccountSetting(minutesBefore = 15))
        repository.resync("account 15")
        assertEquals(setOf(15), pendingOffsets())

        // 設定画面のスイッチと同じ呼び方: 何分前は選んでいないので null のまま渡す
        repository.setCalendarPref("1", enabled = false, minutesBefore = null)
        assertEquals(emptySet<Int>(), pendingOffsets())
        repository.setCalendarPref("1", enabled = true, minutesBefore = null)
        assertNull(db.calendarPrefs().all().single().minutesBefore)

        settings.setAccountSetting("work@example.com", AccountSetting(minutesBefore = 30))
        repository.resync("account 30")
        assertEquals(setOf(30), pendingOffsets())
    }

    @Test
    fun `予定はあるのにカレンダー一覧が空の時は、前回の予約をそのまま残す`() = runBlocking {
        settings.setAccountSetting("work@example.com", AccountSetting(minutesBefore = 15))
        repository.resync("account 15")
        assertEquals(setOf(15), pendingOffsets())
        // アカウントへ当てはめられないので、既定の分数へ倒したり止めたりしない
        OneAccountProvider.calendarsReadable = false
        repository.resync("calendar list unreadable")
        assertEquals(setOf(15), pendingOffsets())
    }

    @Test
    fun `カレンダーで何分前を選んだ時だけ、アカウントの既定より勝つ`() = runBlocking {
        settings.setAccountSetting("work@example.com", AccountSetting(minutesBefore = 15))
        repository.setCalendarPref("1", enabled = true, minutesBefore = 5)
        assertEquals(setOf(5), pendingOffsets())
        // 既定に戻すとアカウントの値に従う
        repository.setCalendarPref("1", enabled = true, minutesBefore = null)
        assertEquals(setOf(15), pendingOffsets())
    }

    @Test
    fun `旧版の行はアプリ既定と同じ分数なら既定に従い、違う分数は選んだ値として残る`() {
        val name = "migration-5-6-test.db"
        context.deleteDatabase(name)
        // v5 の calendar_prefs を作る (NOT NULL の分数列)
        val v5 = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE calendar_prefs (calendarId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, " +
                                "minutesBefore INTEGER NOT NULL, allDayMinutes INTEGER NOT NULL DEFAULT 540, " +
                                "extraOffsetsCsv TEXT NOT NULL DEFAULT '', startAction TEXT NOT NULL DEFAULT 'ALARM', " +
                                "reminderAction TEXT NOT NULL DEFAULT 'ALARM')",
                        )
                        db.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore, allDayMinutes) VALUES ('1', 0, 10, 480)")
                        db.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore) VALUES ('2', 1, 5)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val raw = v5.writableDatabase
        Migration5To6 { 10 }.migrate(raw)
        val rows = raw.query("SELECT calendarId, enabled, minutesBefore, allDayMinutes FROM calendar_prefs ORDER BY calendarId").use { c ->
            buildList {
                while (c.moveToNext()) add(listOf(c.getString(0), c.getInt(1), if (c.isNull(2)) null else c.getInt(2), c.getInt(3)))
            }
        }
        v5.close()
        context.deleteDatabase(name)
        // 既定 (10) と同じ行は null に、他の列はそのまま
        assertEquals(listOf("1", 0, null, 480), rows[0])
        assertEquals(listOf("2", 1, 5, 540), rows[1])
    }

    @Test
    fun `v5のDBをアプリと同じ移行一覧で開くとRoomのスキーマ検査を通って読める`() = runBlocking {
        val name = "migration-5-6-room.db"
        context.deleteDatabase(name)
        // いったん今の版で作り、calendar_prefs と audit_log を v5 の形へ戻して版を 5 にする (v6→v7 も続けて通す)
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().also { it.openHelper.writableDatabase }.close()
        val file = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE audit_log")
            raw.execSQL(
                "CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "atMillis INTEGER NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)",
            )
            raw.execSQL("DROP TABLE calendar_prefs")
            raw.execSQL(
                "CREATE TABLE calendar_prefs (calendarId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, " +
                    "minutesBefore INTEGER NOT NULL, allDayMinutes INTEGER NOT NULL DEFAULT 540, " +
                    "extraOffsetsCsv TEXT NOT NULL DEFAULT '', startAction TEXT NOT NULL DEFAULT 'ALARM', " +
                    "reminderAction TEXT NOT NULL DEFAULT 'ALARM')",
            )
            raw.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore) VALUES ('1', 1, 0)")
            raw.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore) VALUES ('2', 1, 30)")
            raw.version = 5
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS, Migration5To6 { 0 })
            .build()
        val prefs = migrated.calendarPrefs().all().associateBy { it.calendarId }
        migrated.close()
        context.deleteDatabase(name)
        assertNull(prefs["1"]?.minutesBefore)
        assertEquals(30, prefs["2"]?.minutesBefore)
    }
}
