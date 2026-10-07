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
                "SELECT instanceKey, CASE WHEN muted = 1 THEN 'MUTE' ELSE NULL END, " +
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

@Database(
    entities = [
        ScheduledInstanceEntity::class,
        StandaloneAlarmEntity::class,
        CalendarPrefEntity::class,
        EventOverrideEntity::class,
        AuditLogEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        /** Room.databaseBuilder に渡すマイグレーション一覧。 */
        val MIGRATIONS = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
    }
    abstract fun scheduledInstances(): ScheduledInstanceDao
    abstract fun standaloneAlarms(): StandaloneAlarmDao
    abstract fun calendarPrefs(): CalendarPrefDao
    abstract fun eventOverrides(): EventOverrideDao
    abstract fun auditLog(): AuditLogDao
}
