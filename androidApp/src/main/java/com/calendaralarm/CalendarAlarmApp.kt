package com.calendaralarm

import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
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
            .addMigrations(
                object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // v2: 終日イベントの鳴動時刻 + 複数リマインダーの列を追加
                        db.execSQL("ALTER TABLE calendar_prefs ADD COLUMN allDayMinutes INTEGER NOT NULL DEFAULT 540")
                        db.execSQL("ALTER TABLE calendar_prefs ADD COLUMN extraOffsetsCsv TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE event_overrides ADD COLUMN extraOffsetsCsv TEXT")
                    }
                },
                *AppDatabase.MIGRATIONS,
            )
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
            onMissed = { n ->
                runCatching {
                    com.calendaralarm.engine.MissedNotifier.postMissed(app, n)
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
        ensureCalendarObserver()
    }

    private var calendarObserverRegistered = false

    /**
     * カレンダー内容の変更検知。イベントの追加/移動/削除を拾って
     * 即時 WorkManager 経由で予約を再整合させる (12h 周期待ちを防ぐ)。
     * enqueueUniqueWork(REPLACE) により連続変更はデバウンスされる。
     *
     * READ_CALENDAR 未付与で登録すると SecurityException で
     * Application.onCreate ごと落ちる (権限なし初回起動で実害が出た)。
     * そのため権限がある時だけ登録し、オンボーディングや設定アプリで
     * 後から権限が付いたケースは MainActivity.onResume からの呼び出しで拾う。
     */
    fun ensureCalendarObserver() {
        if (calendarObserverRegistered) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CALENDAR,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        calendarObserverRegistered = runCatching {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    runCatching { SyncWorker.enqueueNow(this@CalendarAlarmApp, "calendar change") }
                }
            }
            contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI, true, observer,
            )
            // リマインダー単独の変更が Events URI に通知されない端末でも
            // 「○分前」の変更を拾って再予約する。
            contentResolver.registerContentObserver(
                CalendarContract.Reminders.CONTENT_URI, true, observer,
            )
            true
        }.getOrDefault(false)
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
