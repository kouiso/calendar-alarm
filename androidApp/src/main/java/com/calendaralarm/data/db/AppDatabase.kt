package com.calendaralarm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 → v3: 予定アクションの3状態化 (muted→action)、カレンダー別開始/リマインダーアクション、通知専用デリバリ。 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // muted を落とすためテーブルごと作り替える (Room はスキーマ完全一致を要求)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS event_overrides_new (" +
                "instanceKey TEXT NOT NULL PRIMARY KEY, action TEXT, " +
                "minutesBefore INTEGER, extraOffsetsCsv TEXT)",
        )
        db.execSQL(
            "INSERT INTO event_overrides_new (instanceKey, action, minutesBefore, extraOffsetsCsv) " +
                // muted=0 の行は「ミュートを解除した」明示的意思として ALARM に写す。
                // NULL (既定追従) に潰すと never コード等で意図に反して鳴らなくなる
                "SELECT instanceKey, CASE WHEN muted = 1 THEN 'MUTE' ELSE 'ALARM' END, " +
                "minutesBefore, extraOffsetsCsv FROM event_overrides",
        )
        db.execSQL("DROP TABLE event_overrides")
        db.execSQL("ALTER TABLE event_overrides_new RENAME TO event_overrides")
        db.execSQL("ALTER TABLE calendar_prefs ADD COLUMN startAction TEXT NOT NULL DEFAULT 'ALARM'")
        db.execSQL("ALTER TABLE calendar_prefs ADD COLUMN reminderAction TEXT NOT NULL DEFAULT 'ALARM'")
        db.execSQL("ALTER TABLE scheduled_instances ADD COLUMN delivery TEXT NOT NULL DEFAULT 'ALARM'")
    }
}

/** v3 → v4: 「ロック解除までミュート」フラグ (単発アラーム + 予約スナップショット)。 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE standalone_alarms ADD COLUMN muteUntilUnlock INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE scheduled_instances ADD COLUMN muteUntilUnlock INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 繰返しモード拡張: 旧行は repeatMode=null のまま daysMask から実効値を導出する
        db.execSQL("ALTER TABLE standalone_alarms ADD COLUMN repeatMode TEXT")
        db.execSQL("ALTER TABLE standalone_alarms ADD COLUMN repeatInterval INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE standalone_alarms ADD COLUMN repeatAnchorMillis INTEGER")
    }
}

/**
 * v5 → v6: カレンダーの「何分前」を「選んでいない (null)」で持てるようにする (#74)。
 *
 * 旧版はスイッチや終日の時刻を変えただけでも、その時のアプリ既定の分数を行に書き込んでいた。
 * 選んだ値と区別できないので、アプリ既定と同じ値の行は「選んでいない」とみなして null にする。
 * 移行の時点ではアカウントの既定がまだ無いため、鳴る時刻はどの行も変わらない。
 * 既定と違う値の行は利用者が選んだものとして残す。
 */
class Migration5To6(private val appDefaultMinutes: () -> Int) : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 列の NOT NULL は ALTER で外せないため作り替える
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS calendar_prefs_new (" +
                "calendarId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, minutesBefore INTEGER, " +
                "allDayMinutes INTEGER NOT NULL DEFAULT 540, extraOffsetsCsv TEXT NOT NULL DEFAULT '', " +
                "startAction TEXT NOT NULL DEFAULT 'ALARM', reminderAction TEXT NOT NULL DEFAULT 'ALARM')",
        )
        db.execSQL(
            "INSERT INTO calendar_prefs_new " +
                "(calendarId, enabled, minutesBefore, allDayMinutes, extraOffsetsCsv, startAction, reminderAction) " +
                "SELECT calendarId, enabled, CASE WHEN minutesBefore = ? THEN NULL ELSE minutesBefore END, " +
                "allDayMinutes, extraOffsetsCsv, startAction, reminderAction FROM calendar_prefs",
            arrayOf<Any>(appDefaultMinutes()),
        )
        db.execSQL("DROP TABLE calendar_prefs")
        db.execSQL("ALTER TABLE calendar_prefs_new RENAME TO calendar_prefs")
    }
}

/**
 * v6 → v7: 鳴動ログに予約の id・鳴動時刻・タイトルを持たせる (#51 PR3)。
 * 既存の停止・スヌーズ・鳴らずの行は、まだ残っている予約の行から埋める。
 * 予約の行が既に消えた古い行は埋めない (以前の数え方でも数えていなかった行)。
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE audit_log ADD COLUMN instanceId TEXT")
        db.execSQL("ALTER TABLE audit_log ADD COLUMN triggerAtMillis INTEGER")
        db.execSQL("ALTER TABLE audit_log ADD COLUMN title TEXT")
        // detail の書式: MISS は "id" か "id: 理由"、DISMISS は "id"、SNOOZE は "id +N分 → 次の id"
        val match = "(" +
            "(audit_log.action IN ('MISS', 'DISMISS') AND audit_log.detail = s.id) OR " +
            "(audit_log.action = 'MISS' AND substr(audit_log.detail, 1, length(s.id) + 2) = s.id || ': ') OR " +
            "(audit_log.action = 'SNOOZE' AND substr(audit_log.detail, 1, length(s.id) + 2) = s.id || ' +')" +
            ")"
        for (column in listOf("instanceId" to "s.id", "triggerAtMillis" to "s.triggerAtMillis", "title" to "s.title")) {
            db.execSQL(
                "UPDATE audit_log SET ${column.first} = " +
                    "(SELECT ${column.second} FROM scheduled_instances s WHERE $match LIMIT 1) " +
                    "WHERE action IN ('MISS', 'DISMISS', 'SNOOZE')",
            )
        }
    }
}

@Database(
    entities = [
        ScheduledInstanceEntity::class,
        StandaloneAlarmEntity::class,
        CalendarPrefEntity::class,
        EventOverrideEntity::class,
        AuditLogEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        /** Room.databaseBuilder に渡すマイグレーション一覧。v5→v6 はアプリ既定の分数が要るので [Migration5To6] を別に足す */
        val MIGRATIONS = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7)
    }
    abstract fun scheduledInstances(): ScheduledInstanceDao
    abstract fun standaloneAlarms(): StandaloneAlarmDao
    abstract fun calendarPrefs(): CalendarPrefDao
    abstract fun eventOverrides(): EventOverrideDao
    abstract fun auditLog(): AuditLogDao
}
