package com.calendaralarm

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.calendar.CalendarContractReader
import com.calendaralarm.data.db.AppDatabase
import com.calendaralarm.data.sync.SyncWorker
import com.calendaralarm.engine.AlarmScheduler
import com.calendaralarm.shared.weather.WeatherApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 手動DIコンテナ。規模から見て DI フレームワークを入れる必要がない。 */
class AppContainer(val app: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "calendar-alarm.db")
            .build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    val scheduler: AlarmScheduler by lazy { AlarmScheduler(app) }

    val calendarReader: CalendarContractReader by lazy { CalendarContractReader(app) }

    val weather: WeatherApi by lazy { WeatherApi() }

    val repository: AlarmRepository by lazy {
        AlarmRepository(
            db = db,
            scheduler = scheduler,
            calendarReader = calendarReader,
            settings = settings,
            hasCalendarPermission = {
                ContextCompat.checkSelfPermission(
                    app, android.Manifest.permission.READ_CALENDAR,
                ) == PackageManager.PERMISSION_GRANTED
            },
            appScope = appScope,
            onScheduleChanged = {
                runCatching {
                    com.calendaralarm.widget.NextAlarmWidget().updateAll(app)
                }
            },
        )
    }
}

class CalendarAlarmApp : Application() {

    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container
        scheduleHealthCheck()
    }

    /** 12時間ごとの健全性チェック。DB の PENDING 予約を AlarmManager に再主張する。 */
    private fun scheduleHealthCheck() {
        // WorkManager が初期化されていない環境 (テスト等) でもアプリを落とさない
        runCatching {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWorker.PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
