package com.calendaralarm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScheduledInstanceEntity::class,
        StandaloneAlarmEntity::class,
        CalendarPrefEntity::class,
        EventOverrideEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduledInstances(): ScheduledInstanceDao
    abstract fun standaloneAlarms(): StandaloneAlarmDao
    abstract fun calendarPrefs(): CalendarPrefDao
    abstract fun eventOverrides(): EventOverrideDao
    abstract fun auditLog(): AuditLogDao
}
