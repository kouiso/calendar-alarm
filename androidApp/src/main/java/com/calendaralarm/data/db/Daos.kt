package com.calendaralarm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledInstanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ScheduledInstanceEntity>)

    @Query("SELECT * FROM scheduled_instances WHERE state = 'PENDING'")
    suspend fun pending(): List<ScheduledInstanceEntity>

    @Query("SELECT * FROM scheduled_instances WHERE state = 'PENDING' ORDER BY triggerAtMillis ASC")
    fun pendingFlow(): Flow<List<ScheduledInstanceEntity>>

    @Query("SELECT * FROM scheduled_instances WHERE id = :id")
    suspend fun byId(id: String): ScheduledInstanceEntity?

    @Query("SELECT * FROM scheduled_instances")
    suspend fun all(): List<ScheduledInstanceEntity>

    @Query("UPDATE scheduled_instances SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("UPDATE scheduled_instances SET state = :state WHERE id IN (:ids)")
    suspend fun setStates(ids: List<String>, state: String)

    @Query("SELECT * FROM scheduled_instances ORDER BY triggerAtMillis ASC")
    fun allFlow(): Flow<List<ScheduledInstanceEntity>>
}

@Dao
interface StandaloneAlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: StandaloneAlarmEntity): Long

    @Query("SELECT * FROM standalone_alarms ORDER BY hour, minute")
    fun allFlow(): Flow<List<StandaloneAlarmEntity>>

    @Query("SELECT * FROM standalone_alarms")
    suspend fun all(): List<StandaloneAlarmEntity>

    @Query("SELECT * FROM standalone_alarms WHERE id = :id")
    suspend fun byId(id: Long): StandaloneAlarmEntity?

    @Query("DELETE FROM standalone_alarms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CalendarPrefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: CalendarPrefEntity)

    @Query("SELECT * FROM calendar_prefs")
    fun allFlow(): Flow<List<CalendarPrefEntity>>

    @Query("SELECT * FROM calendar_prefs")
    suspend fun all(): List<CalendarPrefEntity>
}

@Dao
interface EventOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: EventOverrideEntity)

    @Query("SELECT * FROM event_overrides")
    suspend fun all(): List<EventOverrideEntity>

    @Query("SELECT * FROM event_overrides")
    fun allFlow(): Flow<List<EventOverrideEntity>>

    @Query("DELETE FROM event_overrides WHERE instanceKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(log: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY atMillis DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_log WHERE atMillis < :before")
    suspend fun prune(before: Long)
}
