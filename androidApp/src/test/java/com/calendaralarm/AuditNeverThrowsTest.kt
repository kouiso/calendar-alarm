package com.calendaralarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.engine.AlarmScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 鳴動ログの書き込み失敗が鳴動・停止の経路を落とさないこと (#46)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditNeverThrowsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun repo(db: AppDatabase) = AlarmRepository(
        db = db,
        scheduler = AlarmScheduler(context),
        calendarReader = CalendarContractReader(context),
        settings = SettingsRepository(context),
        hasCalendarPermission = { false },
        appScope = CoroutineScope(Dispatchers.IO),
    )

    private fun memoryDb() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Test
    fun closedDatabaseDoesNotThrow() = runBlocking {
        val db = memoryDb()
        val repository = repo(db)
        db.close()
        // 直す前はここで例外が出て、独立コルーチンならプロセスが落ちていた
        repository.audit("X", "y")
    }

    @Test
    fun brokenTableDoesNotThrow() = runBlocking {
        val db = memoryDb()
        val repository = repo(db)
        // 閉じた DB は取り消し扱いの例外になるので、実際の DB 障害 (SQLiteException) は表を消して起こす
        db.openHelper.writableDatabase.execSQL("DROP TABLE audit_log")
        repository.audit("X", "y")
        db.close()
    }

    @Test
    fun failureInsideDetachedCoroutineNeverReachesUncaughtHandler() = runBlocking {
        val db = memoryDb()
        val repository = repo(db)
        db.openHelper.writableDatabase.execSQL("DROP TABLE audit_log")
        val uncaught = CompletableDeferred<Throwable?>()
        // 鳴動経路と同じ「ハンドラ無しの独立スコープで launch」を再現し、漏れた例外を捕まえる
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught.complete(e) },
        )
        scope.launch { repository.audit("FIRE", "detached") }.join()
        uncaught.complete(null)
        assertNull(uncaught.await())
        scope.cancel()
        db.close()
    }

    @Test
    fun callerCancellationStillPropagates() = runBlocking {
        val db = memoryDb()
        val repository = repo(db)
        var cancelledSeen = false
        val job = launch(Dispatchers.IO) {
            this.cancel()
            try {
                repository.audit("X", "cancelled caller")
            } catch (e: CancellationException) {
                cancelledSeen = true
                throw e
            }
        }
        job.join()
        assertTrue("呼び出し側の取り消しは握りつぶさない", cancelledSeen)
        db.close()
    }

    @Test
    fun healthyDatabaseStillWrites() = runBlocking {
        val db = memoryDb()
        val repository = repo(db)
        repository.audit("OK", "written")
        val logs = repository.auditFlow(10).first()
        assertEquals(1, logs.size)
        assertEquals("OK", logs.single().action)
        db.close()
    }
}
