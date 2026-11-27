package com.calendaralarm

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.MIGRATION_7_8
import com.calendaralarm.data.db.Migration5To6
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 設定の主キーを端末の行番号から鍵へ替える移行 (v7 → v8) で、行が消えず旧形式の鍵で写る (#268) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarPrefKeyMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `版 7 の calendar_prefs は id の鍵で写され、行が消えない`() {
        val name = "migration-7-8-test.db"
        context.deleteDatabase(name)
        val v7 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE calendar_prefs (calendarId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, " +
                                "minutesBefore INTEGER, allDayMinutes INTEGER NOT NULL DEFAULT 540, " +
                                "extraOffsetsCsv TEXT NOT NULL DEFAULT '', startAction TEXT NOT NULL DEFAULT 'ALARM', " +
                                "reminderAction TEXT NOT NULL DEFAULT 'ALARM')",
                        )
                        db.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore, allDayMinutes) VALUES ('1', 0, NULL, 480)")
                        db.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore) VALUES ('2', 1, 5)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val raw = v7.writableDatabase
        MIGRATION_7_8.migrate(raw)
        val rows = raw.query(
            "SELECT calendarKey, calendarId, enabled, minutesBefore, allDayMinutes, accountType, accountName " +
                "FROM calendar_prefs ORDER BY calendarId",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        listOf(
                            c.getString(0), c.getString(1), c.getInt(2),
                            if (c.isNull(3)) null else c.getInt(3), c.getInt(4), c.getString(5), c.getString(6),
                        ),
                    )
                }
            }
        }
        v7.close()
        context.deleteDatabase(name)
        assertEquals(2, rows.size)
        assertEquals(listOf("id:1", "1", 0, null, 480, "", ""), rows[0])
        assertEquals(listOf("id:2", "2", 1, 5, 540, "", ""), rows[1])
    }

    @Test
    fun `版 7 の DB を今の版で開ける`() = runBlocking {
        val name = "migration-7-8-room.db"
        context.deleteDatabase(name)
        // いったん今の版で作り、calendar_prefs を v7 の形へ戻して版を 7 にする
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().also { it.openHelper.writableDatabase }.close()
        val file = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE calendar_prefs")
            raw.execSQL(
                "CREATE TABLE calendar_prefs (calendarId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, " +
                    "minutesBefore INTEGER, allDayMinutes INTEGER NOT NULL DEFAULT 540, " +
                    "extraOffsetsCsv TEXT NOT NULL DEFAULT '', startAction TEXT NOT NULL DEFAULT 'ALARM', " +
                    "reminderAction TEXT NOT NULL DEFAULT 'ALARM')",
            )
            raw.execSQL("INSERT INTO calendar_prefs (calendarId, enabled, minutesBefore) VALUES ('1', 0, NULL)")
            raw.version = 7
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS, Migration5To6 { 0 })
            .build()
        val prefs = migrated.calendarPrefs().all()
        migrated.close()
        context.deleteDatabase(name)
        assertEquals(1, prefs.size)
        assertEquals("id:1", prefs[0].calendarKey)
        assertEquals("1", prefs[0].calendarId)
    }
}
