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
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 設定で「何分前」「スヌーズ」の既定を変えたら、resync を呼ばなくても予約済みのアラームが新しい分数になる (#231 #254)。
 * 画面と同じく SettingsRepository の setter だけを呼ぶ。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleDefaultsResyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope
    private val start = Clock.System.now().toEpochMilliseconds() + 3 * 3_600_000L

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
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        OneEventProvider.eventStart = start
        Robolectric.buildContentProvider(OneEventProvider::class.java).create(CalendarContract.AUTHORITY)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = settings,
            hasCalendarPermission = { true },
            appScope = scope,
        )
    }

    // DataStore は同じ JVM の他の試験クラスと共有されるので、既定の分数を残さない
    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    /** 予約中のこの予定のアラーム */
    private suspend fun pending(): List<ScheduledInstanceEntity> =
        db.scheduledInstances().all()
            .filter { it.state == AlarmState.PENDING.name && it.id.startsWith("ev:1:1:$start:") }

    private fun waitUntil(timeoutMs: Long, cond: suspend () -> Boolean): Boolean = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return@runBlocking true
            Thread.sleep(50)
        }
        cond()
    }

    /** 見張りを始め、最初の値を受け取るまで待つ (最初の値では再同期しないので、その後に変える) */
    private fun startWatcher(source: Flow<SettingsRepository.Settings> = settings.flow) {
        val ready = java.util.concurrent.CountDownLatch(1)
        repository.watchScheduleSettings(scope, { ready.countDown() }, source)
        assertTrue("見張りが最初の値を受け取ること", ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    /** 見張りが呼んだ再同期の回数。理由は末尾の空白まで入れる (他の理由と取り違えない) */
    private suspend fun settingsResyncCount(): Int =
        db.auditLog().recentByActionsFlow(listOf("RESYNC"), 50).first()
            .count { it.detail.startsWith("reason=settings ") }

    @Test
    fun `何分前の既定を変えると、resync を呼ばなくても予約の時刻が新しい分数になる`() {
        runBlocking { repository.resync("start") }
        val before = runBlocking { pending() }
        assertEquals("最初は開始時に鳴る", listOf(start), before.map { it.triggerAtMillis })
        startWatcher()

        runBlocking { settings.setDefaultMinutesBefore(15) }

        assertTrue(
            "15分前に予約し直されること",
            waitUntil(5_000) { pending().map { it.triggerAtMillis } == listOf(start - 15 * 60_000L) },
        )
    }

    @Test
    fun `スヌーズの既定を変えると、resync を呼ばなくても予約のスヌーズ分数が新しい値になる`() {
        runBlocking { repository.resync("start") }
        assertEquals(listOf(10), runBlocking { pending() }.map { it.snoozeMinutes })
        startWatcher()

        runBlocking { settings.setDefaultSnoozeMinutes(5) }

        assertTrue(
            "スヌーズ5分で予約し直されること",
            waitUntil(5_000) { pending().map { it.snoozeMinutes } == listOf(5) },
        )
    }

    @Test
    fun `見張る設定は予約に効く12欄`() {
        // 予約に効く欄を足した時に、ここを直し忘れないための固定
        assertEquals(
            listOf(
                "defaultMinutesBefore", "defaultSnoozeMinutes", "defaultStartAction", "defaultReminderAction",
                "titleCodes", "inviteFilter", "eventTypeFilter", "googleAccountEmails", "accountSettings",
                "importEventReminders", "extraReminderOffsets", "muteAll",
            ),
            AlarmRepository.ScheduleSettings::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name },
        )
    }

    @Test
    fun `すべてミュートにすると、resync を呼ばなくても予定のアラームの予約が取り消される`() {
        runBlocking { repository.resync("start") }
        assertEquals(1, runBlocking { pending() }.size)
        startWatcher()

        runBlocking { settings.setMuteAll(true) }

        assertTrue("予約が取り消されること", waitUntil(5_000) { pending().isEmpty() })
    }

    @Test
    fun `予定の種類を鳴らさないにすると、resync を呼ばなくても予約が取り消される`() {
        runBlocking { repository.resync("start") }
        assertEquals(1, runBlocking { pending() }.size)
        startWatcher()

        runBlocking { settings.toggleEventType(EventType.EVENT) }

        assertTrue("予約が取り消されること", waitUntil(5_000) { pending().isEmpty() })
    }

    @Test
    fun `開始の既定の動作を鳴らさないにすると、resync を呼ばなくても予約が取り消される`() {
        runBlocking { repository.resync("start") }
        assertEquals(1, runBlocking { pending() }.size)
        startWatcher()

        runBlocking { settings.setDefaultStartAction(EventAction.MUTE) }

        assertTrue("予約が取り消されること", waitUntil(5_000) { pending().isEmpty() })
    }

    @Test
    fun `全イベントへの追加リマインダーを足すと、resync を呼ばなくても予約が増える`() {
        runBlocking { repository.resync("start") }
        startWatcher()

        runBlocking { settings.setExtraReminderOffsets(listOf(30)) }

        assertTrue(
            "30分前の予約が増えること",
            waitUntil(5_000) { pending().any { it.id == "ev:1:1:$start:b30" } },
        )
    }

    @Test
    fun `設定を1つ変えると再同期は1回だけ走る`() {
        runBlocking { repository.resync("start") }
        startWatcher()

        runBlocking { settings.setMuteAll(true) }
        assertTrue("予約が取り消されること", waitUntil(5_000) { pending().isEmpty() })
        Thread.sleep(500)

        assertEquals(1, runBlocking { settingsResyncCount() })
    }

    @Test
    fun `予約に効かない設定を変えても再同期しない`() {
        runBlocking { repository.resync("start") }
        startWatcher()

        runBlocking { settings.setShowWeekNumbers(true) }
        runBlocking { settings.setMuteAll(true) }

        assertTrue("予約が取り消されること", waitUntil(5_000) { pending().isEmpty() })
        Thread.sleep(500)
        // 週番号の分は数えない。ミュートの1回だけ
        assertEquals(1, runBlocking { settingsResyncCount() })
    }

    @Test
    fun `設定の読み取りが一度失敗しても、見張りは止まらずに次の変更で再同期する`() {
        runBlocking { repository.resync("start") }
        assertEquals(1, runBlocking { pending() }.size)
        val subscriptions = java.util.concurrent.atomic.AtomicInteger(0)
        // 1回目の購読だけ読み取りに失敗し、2回目から本物の設定を流す
        val flaky = flow {
            if (subscriptions.getAndIncrement() == 0) throw java.io.IOException("読み取りの失敗 (試験)")
            emitAll(settings.flow)
        }
        startWatcher(flaky)

        runBlocking { settings.setMuteAll(true) }

        assertTrue("読み直した後の変更で予約が取り消されること", waitUntil(10_000) { pending().isEmpty() })
    }
}
