package com.calendaralarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.data.db.MIGRATION_5_6
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「鳴動の状態」の見逃しの数と「最近の鳴動」の元になる鳴動ログ (#51 PR3)。
 * 予約の行は単発アラームを使い直すと消えるので、数えるのはログの方であることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingHistoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository
    private val hour = 60L * 60 * 1000

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { false },
            appScope = CoroutineScope(Dispatchers.IO),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: String, triggerAt: Long, alarmId: Long? = null, title: String = "朝会") =
        ScheduledInstanceEntity.of(
            AlarmInstance(
                id = id,
                triggerAtMillis = triggerAt,
                title = title,
                kind = if (alarmId != null) AlarmKind.STANDALONE else AlarmKind.EVENT,
                standaloneAlarmId = alarmId,
            ),
        )

    @Test
    fun ringLogFiltersByActionBeforeTheLimit() = runBlocking {
        val now = System.currentTimeMillis()
        db.auditLog().insert(AuditLogEntity(atMillis = now - 3 * hour, action = "DISMISS", detail = "a"))
        db.auditLog().insert(AuditLogEntity(atMillis = now - 2 * hour, action = "SNOOZE", detail = "b"))
        db.auditLog().insert(AuditLogEntity(atMillis = now - hour, action = "MISS", detail = "c"))
        db.auditLog().insert(AuditLogEntity(atMillis = now - hour, action = "FIRE", detail = "c"))
        // 鳴動の後に再同期などの記録が大量に入っても、鳴動の結果3件は押し出されない (14e739e の回帰)
        repeat(20) { db.auditLog().insert(AuditLogEntity(atMillis = now - it, action = "RESYNC", detail = "r$it")) }

        val recent = repository.recentRingLogFlow(3).first()

        assertEquals(listOf("MISS", "SNOOZE", "DISMISS"), recent.map { it.action })
    }

    @Test
    fun missedCountUsesTriggerTimeAndCountsEachAlarmOnce() = runBlocking {
        val now = System.currentTimeMillis()
        // 鳴動時刻が2時間前: 2つの経路から鳴らずと書かれても1件
        db.auditLog().insert(AuditLogEntity(atMillis = now, action = "MISS", detail = "x", instanceId = "x", triggerAtMillis = now - 2 * hour))
        db.auditLog().insert(AuditLogEntity(atMillis = now, action = "MISS", detail = "x: 理由", instanceId = "x", triggerAtMillis = now - 2 * hour))
        // 鳴動時刻が30時間前: 見つけたのが今でも24時間の外 (Android と iOS で同じ扱い)
        db.auditLog().insert(AuditLogEntity(atMillis = now, action = "MISS", detail = "y", instanceId = "y", triggerAtMillis = now - 30 * hour))
        // 鳴らず以外は数えない
        db.auditLog().insert(AuditLogEntity(atMillis = now, action = "DISMISS", detail = "z", instanceId = "z", triggerAtMillis = now - hour))

        assertEquals(1, repository.recentMissedFlow(nowMillis = { now }).first())
    }

    @Test
    fun missedOneShotStaysCountedAfterItIsTurnedBackOn() = runBlocking {
        val now = System.currentTimeMillis()
        val alarm = StandaloneAlarm(id = 7, enabled = false, hour = 6, minute = 40, label = "朝")
        repository.upsertStandaloneAlarm(alarm)
        db.scheduledInstances().upsert(listOf(row("sa:7:2020-01-01", now - 2 * hour, alarmId = 7, title = "朝")))
        repository.markMissed("sa:7:2020-01-01", "鳴動時刻から120分経過")
        assertEquals(1, repository.recentMissedFlow(nowMillis = { now }).first())

        // 単発アラームを入れ直すと、消費のリセットで終端の行が消える
        repository.upsertStandaloneAlarm(alarm.copy(enabled = true))
        assertNull(db.scheduledInstances().byId("sa:7:2020-01-01"))

        // 直す前は予約の行で数えていたのでここが 0 になり、設定と予定画面が「鳴る準備OK」に戻っていた
        assertEquals(1, repository.recentMissedFlow(nowMillis = { now }).first())
        val miss = repository.recentRingLogFlow(3).first().first { it.action == "MISS" }
        assertEquals("朝", miss.title)
        assertEquals(now - 2 * hour, miss.triggerAtMillis)
    }

    @Test
    fun dismissAndSnoozeKeepTheAlarmTitleAndTime() = runBlocking {
        val now = System.currentTimeMillis()
        db.scheduledInstances().upsert(listOf(row("ev:a:b0", now - hour, title = "歯医者"), row("ev:b:b0", now - 2 * hour, title = "会議")))
        repository.onDismissed("ev:a:b0")
        repository.onSnoozed("ev:b:b0", 5)

        val recent = repository.recentRingLogFlow(3).first().associateBy { it.action }

        assertEquals("歯医者", recent.getValue("DISMISS").title)
        assertEquals(now - hour, recent.getValue("DISMISS").triggerAtMillis)
        assertEquals("会議", recent.getValue("SNOOZE").title)
        assertEquals(now - 2 * hour, recent.getValue("SNOOZE").triggerAtMillis)
    }

    @Test
    fun migrationFillsRingFactsFromTheRowsThatStillExist() {
        val sql = db.openHelper.writableDatabase
        val now = System.currentTimeMillis()
        val fresh = columns("audit_log")
        // v5 の audit_log に戻す (scheduled_instances は v5 と v6 で同じ)
        sql.execSQL("DROP TABLE audit_log")
        sql.execSQL(
            "CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "atMillis INTEGER NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)",
        )
        runBlocking {
            db.scheduledInstances().upsert(
                listOf(
                    row("sa:1:2026-10-21", now - hour, alarmId = 1, title = "朝").copy(state = AlarmState.MISSED.name),
                    row("sa:1:2026-10-21:snz1", now - 3 * hour, alarmId = 1, title = "朝").copy(state = AlarmState.DISMISSED.name),
                    row("ev:k:b0", now - 2 * hour, title = "会議").copy(state = AlarmState.SNOOZED.name),
                ),
            )
        }
        val insert = "INSERT INTO audit_log (atMillis, action, detail) VALUES (?, ?, ?)"
        sql.execSQL(insert, arrayOf<Any>(now, "MISS", "sa:1:2026-10-21: 鳴動時刻から60分経過"))
        sql.execSQL(insert, arrayOf<Any>(now, "DISMISS", "sa:1:2026-10-21:snz1"))
        sql.execSQL(insert, arrayOf<Any>(now, "SNOOZE", "ev:k:b0 +5m → ev:k:b0:snz1"))
        sql.execSQL(insert, arrayOf<Any>(now, "MISS", "sa:9:2026-10-01"))
        sql.execSQL(insert, arrayOf<Any>(now, "RESYNC", "sa:1:2026-10-21"))

        MIGRATION_5_6.migrate(sql)

        // Room が v6 で作る表と列の形が一致する (一致しないと起動時に Room が開けない)
        assertEquals(fresh, columns("audit_log"))
        val rows = runBlocking { db.auditLog().recentFlow(10).first() }.associateBy { it.detail }
        assertEquals("sa:1:2026-10-21", rows.getValue("sa:1:2026-10-21: 鳴動時刻から60分経過").instanceId)
        assertEquals(now - hour, rows.getValue("sa:1:2026-10-21: 鳴動時刻から60分経過").triggerAtMillis)
        // id が前方一致する別の予約 (スヌーズ後の行) と取り違えない
        assertEquals(now - 3 * hour, rows.getValue("sa:1:2026-10-21:snz1").triggerAtMillis)
        assertEquals("会議", rows.getValue("ev:k:b0 +5m → ev:k:b0:snz1").title)
        // 予約の行が既に無い古い行と、鳴動の結果でない行は埋めない
        assertNull(rows.getValue("sa:9:2026-10-01").triggerAtMillis)
        assertNull(rows.getValue("sa:1:2026-10-21").instanceId)
        assertEquals(1, runBlocking { repository.recentMissedFlow(nowMillis = { now }).first() })
    }

    private fun columns(table: String): List<String> {
        val out = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) {
                out += listOf(c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getInt(5)).joinToString("|")
            }
        }
        return out
    }
}
