package com.calendaralarm

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * バックアップから復元された端末では、予約の行と予定ごとの指定を捨てて再同期する (#269)。
 * 印は復元の完了通知だけが置く。通常の起動・初回インストール・この版への更新では何も捨てない。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreResetTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // アプリ本体の起動 (onCreate) が走らせる後始末が印を見終わるまで待つ。
        // 待たないと、この試験が置いた印をアプリ本体の後始末が先に消費しうる
        val app = ApplicationProvider.getApplicationContext<CalendarAlarmApp>()
        runBlocking { withTimeout(10_000) { app.container.restoreGate.await() } }
        RestoreMarker.consume(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        RestoreMarker.consume(context)
    }

    private fun seed() = runBlocking {
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity(
                    id = "a", triggerAtMillis = 1_000L, title = "会議", kind = "EVENT", eventId = "1",
                    standaloneAlarmId = null, snoozeMinutes = 5, soundUri = null, minutesBefore = 10,
                    eventStartMillis = 2_000L, snoozeSeq = 0, state = "PENDING",
                ),
            ),
        )
        db.eventOverrides().upsert(EventOverrideEntity(instanceKey = "1:2000", action = null, minutesBefore = 30))
    }

    private fun counts() = runBlocking {
        db.scheduledInstances().all().size to db.eventOverrides().all().size
    }

    /** BackupAgent は ContextWrapper。システムが onRestoreFinished を呼ぶ時と同じ形で動かす */
    private class TestAgent : RestoreMarkerAgent() {
        fun attach(base: Context) = attachBaseContext(base)
    }

    @Test
    fun restoreFinished_marksThenNextLaunchClearsRowsAndResyncs() {
        seed()
        assertFalse(RestoreMarker.isPending(context))
        TestAgent().also { it.attach(context) }.onRestoreFinished()
        assertTrue(RestoreMarker.isPending(context))

        var resyncs = 0
        val cleared = runBlocking { RestoreReset.runIfRestored(context, db) { resyncs++ } }

        assertTrue(cleared)
        assertEquals(0 to 0, counts())
        assertEquals(1, resyncs)
        assertFalse("印は消費され、次の起動では捨てない", RestoreMarker.isPending(context))
    }

    @Test
    fun restore_clearsCalendarRowIdsAndDropsLegacyPrefs() {
        runBlocking {
            // 鍵つきの行 (端末をまたいで結び付く) と、旧形式の行 (別の端末では結び付けられない)
            db.calendarPrefs().upsert(
                CalendarPrefEntity("5", enabled = false, minutesBefore = null, calendarKey = "com.google\u001Fa@x\u001FS:work"),
            )
            db.calendarPrefs().upsert(CalendarPrefEntity("6", enabled = false, minutesBefore = null))
        }
        RestoreMarker.mark(context)

        runBlocking { RestoreReset.runIfRestored(context, db) { } }

        val rows = runBlocking { db.calendarPrefs().all() }
        assertEquals(1, rows.size)
        assertEquals("com.google\u001Fa@x\u001FS:work", rows[0].calendarKey)
        assertEquals("行番号の控えは空になる", "", rows[0].calendarId)
        assertFalse("旧形式の行は消える", rows.any { it.calendarKey.startsWith("id:") })
    }

    @Test
    fun normalLaunch_withoutMarker_keepsRowsAndDoesNotResync() {
        seed()
        var resyncs = 0
        val cleared = runBlocking { RestoreReset.runIfRestored(context, db) { resyncs++ } }

        assertFalse(cleared)
        assertEquals(1 to 1, counts())
        assertEquals(0, resyncs)
    }

    @Test
    fun firstInstall_emptyDb_isHarmless() {
        var resyncs = 0
        val cleared = runBlocking { RestoreReset.runIfRestored(context, db) { resyncs++ } }
        assertFalse(cleared)
        assertEquals(0 to 0, counts())
        assertEquals(0, resyncs)

        // 空の DB に印があっても落ちない
        RestoreMarker.mark(context)
        val clearedEmpty = runBlocking { RestoreReset.runIfRestored(context, db) { resyncs++ } }
        assertTrue(clearedEmpty)
        assertEquals(0 to 0, counts())
        assertEquals(1, resyncs)
    }

    @Test
    fun markerLivesInNoBackupDir() {
        RestoreMarker.mark(context)
        assertTrue(java.io.File(context.noBackupFilesDir, "restored-from-backup").exists())
    }

    @Test
    fun manifestWiresRestoreMarkerAgentWithFullBackupOnly() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(RestoreMarkerAgent::class.java.name, info.backupAgentName)
        assertTrue((info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
        assertTrue((info.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0)
    }

    @Test
    fun gateOpensAfterDeleteAndBeforeResync() {
        seed()
        RestoreMarker.mark(context)
        val order = mutableListOf<String>()
        runBlocking {
            RestoreReset.runIfRestored(context, db, onCleared = { order += "cleared:${counts()}" }) { order += "resync" }
        }
        assertEquals(listOf("cleared:(0, 0)", "resync"), order)

        // 印が無くても門は開く (開かないと以後の resync が止まる)
        order.clear()
        runBlocking { RestoreReset.runIfRestored(context, db, onCleared = { order += "cleared" }) { order += "resync" } }
        assertEquals(listOf("cleared"), order)
    }

    @Test
    fun launchAtStart_opensGateBeforeResync_andSwallowsResetFailure() = runBlocking {
        seed()
        RestoreMarker.mark(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = CompletableDeferred<Unit>()
            var seenAtResync: Pair<Boolean, Pair<Int, Int>>? = null
            RestoreReset.launchAtStart(scope, context, db, gate) { seenAtResync = gate.isCompleted to counts() }.join()
            assertEquals("resync の時には門が開き、2 表は空", true to (0 to 0), seenAtResync)

            // 後始末が失敗しても門は開き、例外で起動ごと落とさない
            RestoreMarker.mark(context)
            val failingGate = CompletableDeferred<Unit>()
            val job = RestoreReset.launchAtStart(scope, context, db, failingGate) { throw IllegalStateException("boom") }
            job.join()
            assertTrue(failingGate.isCompleted)
            assertFalse("例外を外へ投げない", job.isCancelled)
        } finally {
            scope.cancel()
        }
    }
}
