package com.calendaralarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.EventOverrideEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import kotlinx.coroutines.runBlocking
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
}
