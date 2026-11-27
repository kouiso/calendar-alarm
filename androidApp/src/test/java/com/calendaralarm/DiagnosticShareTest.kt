package com.calendaralarm

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.logic.DiagnosticReport
import com.calendaralarm.ui.collectDiagnosticInput
import com.calendaralarm.ui.diagnosticShareIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** #286 診断の共有: 共有の Intent と、共有する文字の材料の集め方 (題は必ず伏せる)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticShareTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
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
    fun tearDown() {
        db.close()
    }

    @Test
    fun intentCarriesTextAsPlainText() {
        val chooser = diagnosticShareIntent(context, "x")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("text/plain", inner.type)
        assertEquals("x", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun collectsPlatformAndChecks() = runBlocking {
        db.auditLog().insert(
            AuditLogEntity(
                atMillis = 1_000L, action = "DISMISS", detail = "朝活 を止めた",
                instanceId = "sa:1:x", triggerAtMillis = 900L, title = "朝活",
            ),
        )
        val input = collectDiagnosticInput(context, repository, SettingsRepository(context), nowMillis = 2_000L)
        assertEquals("android", input.platform)
        assertEquals(2_000L, input.generatedAtMillis)
        assertTrue(input.checks.any { it.key == "calendar" })
        assertTrue(input.knownTitles.contains("朝活"))
        assertEquals(1, input.log.size)
    }

    @Test
    fun builtTextNeverContainsKnownTitles() = runBlocking {
        db.auditLog().insert(
            AuditLogEntity(
                atMillis = 1_000L, action = "DISMISS", detail = "朝活 を止めた",
                instanceId = "sa:1:x", triggerAtMillis = 900L, title = "朝活",
            ),
        )
        db.auditLog().insert(
            AuditLogEntity(atMillis = 1_500L, action = "ALARM_OFF", detail = "単発アラーム消費で停止: 朝活 (id=3)"),
        )
        db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity(
                    id = "ev:1:2", triggerAtMillis = 9_999_999_999_999L, title = "歯医者", kind = "EVENT",
                    eventId = "1", standaloneAlarmId = null, snoozeMinutes = 10, soundUri = null,
                    minutesBefore = 0, eventStartMillis = null, snoozeSeq = 0, state = "PENDING",
                ),
            ),
        )
        val text = DiagnosticReport.build(
            collectDiagnosticInput(context, repository, SettingsRepository(context), nowMillis = 2_000L),
        )
        assertFalse(text.contains("朝活"))
        assertFalse(text.contains("歯医者"))
        assertTrue(text.contains("titles: hidden"))
        assertTrue(text.contains("| [title] (id=3)"))
    }
}
