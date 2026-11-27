package com.calendaralarm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours

/**
 * 鳴って止めた繰り返しアラームの時刻を、今日の後の時刻へ変えて保存したら、今日その時刻に鳴る (#278 / #241)。
 * 鳴動の id が日付だけだと、止めた回と同じ id になって予約し直されなかった。id に時刻を入れて別の回にする。
 * 作りは ScheduleDefaultsResyncTest と同じ (予定は使わないので権限なしで作る)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandaloneRetimeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = AlarmRepository(
            db = db,
            scheduler = AlarmScheduler(context),
            calendarReader = CalendarContractReader(context),
            settings = SettingsRepository(context),
            hasCalendarPermission = { false },
            appScope = scope,
        )
    }

    // DataStore は同じ JVM の他の試験クラスと共有されるので、設定を残さない
    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private val zone get() = TimeZone.currentSystemDefault()

    /** 今から [hours] 時間後の、現地の時と分 */
    private fun hourMinuteAfter(hours: Int): Pair<Int, Int> {
        val t = (Clock.System.now() + hours.hours).toLocalDateTime(zone)
        return t.hour to t.minute
    }

    /** 今から 2 時間後と 4 時間後が現地の同じ日付か。日付をまたぐと「今日」の意味が変わるので試験を飛ばす */
    private fun assumeSameLocalDate() {
        val first = (Clock.System.now() + 2.hours).toLocalDateTime(zone).date
        val second = (Clock.System.now() + 4.hours).toLocalDateTime(zone).date
        assumeTrue("2時間後と4時間後が同じ日付の時だけ確かめる", first == second)
    }

    private fun everyDay(hour: Int, minute: Int, id: Long = 0) =
        StandaloneAlarm(id = id, hour = hour, minute = minute, daysOfWeek = DayOfWeek.entries.toSet())

    /** 2 時間後の繰り返しアラームを保存し、一番早い回を鳴らして止める。アラームの id と止めた行を返す */
    private suspend fun saveAndDismissFirst(h: Int, m: Int): Pair<Long, com.calendaralarm.data.db.ScheduledInstanceEntity> {
        repository.upsertStandaloneAlarm(everyDay(h, m))
        val alarmId = db.standaloneAlarms().all().single().id
        val first = db.scheduledInstances().pending().minBy { it.triggerAtMillis }
        repository.onFired(first.id)
        repository.onDismissed(first.id)
        assertEquals(AlarmState.DISMISSED.name, db.scheduledInstances().byId(first.id)?.state)
        return alarmId to first
    }

    @Test
    fun `鳴って止めた繰り返しアラームを今日の後の時刻へ変えると、今日その時刻に予約される`() = runBlocking {
        assumeSameLocalDate()
        val (h2, m2) = hourMinuteAfter(2)
        val (alarmId, _) = saveAndDismissFirst(h2, m2)

        val (h4, m4) = hourMinuteAfter(4)
        repository.upsertStandaloneAlarm(everyDay(h4, m4, alarmId))

        // 保存の時刻は分で切り捨てるので、4 時間後の前後 2 分に予約があればよい
        val target = (Clock.System.now() + 4.hours).toEpochMilliseconds()
        val pendingAtTarget = db.scheduledInstances().pending()
            .filter { abs(it.triggerAtMillis - target) <= 120_000L }
        assertEquals("4時間後の PENDING が1件", 1, pendingAtTarget.size)
    }

    @Test
    fun `止めた回の時刻へ戻しても、その回は予約し直さない`() = runBlocking {
        assumeSameLocalDate()
        val (h2, m2) = hourMinuteAfter(2)
        val (alarmId, dismissed) = saveAndDismissFirst(h2, m2)
        val (h4, m4) = hourMinuteAfter(4)
        repository.upsertStandaloneAlarm(everyDay(h4, m4, alarmId))

        // 止めた回の時刻へ戻す
        repository.upsertStandaloneAlarm(everyDay(h2, m2, alarmId))

        assertEquals(AlarmState.DISMISSED.name, db.scheduledInstances().byId(dismissed.id)?.state)
        assertTrue(
            "止めた回と同じ鳴動時刻の PENDING が無いこと",
            db.scheduledInstances().pending().none { it.triggerAtMillis == dismissed.triggerAtMillis },
        )
    }
}
