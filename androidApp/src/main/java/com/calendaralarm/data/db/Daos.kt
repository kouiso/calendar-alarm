package com.calendaralarm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledInstanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ScheduledInstanceEntity>)

    @Query("SELECT * FROM scheduled_instances WHERE state = 'PENDING'")
    suspend fun pending(): List<ScheduledInstanceEntity>

    @Query("SELECT * FROM scheduled_instances WHERE state = 'PENDING' ORDER BY triggerAtMillis ASC")
    fun pendingFlow(): Flow<List<ScheduledInstanceEntity>>

    /** 鳴らせなかった (MISSED) 予約の件数。終了行は7日残るので、24時間の窓を欠けなく数えられる */
    @Query("SELECT COUNT(*) FROM scheduled_instances WHERE state = 'MISSED' AND triggerAtMillis >= :since")
    fun missedSinceFlow(since: Long): Flow<Int>

    @Query("SELECT * FROM scheduled_instances WHERE id = :id")
    suspend fun byId(id: String): ScheduledInstanceEntity?

    /** 1行だけの監視。鳴動画面が全件取得せずに自分の状態変化を追うのに使う。 */
    @Query("SELECT * FROM scheduled_instances WHERE id = :id")
    fun byIdFlow(id: String): Flow<ScheduledInstanceEntity?>

    @Query("SELECT * FROM scheduled_instances")
    suspend fun all(): List<ScheduledInstanceEntity>

    @Query("UPDATE scheduled_instances SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    /** FIRED 化は PENDING/FIRED からのみ。DISMISSED 等の終端を発火で上書きしない (更新行数を返す)。 */
    @Query("UPDATE scheduled_instances SET state = 'FIRED' WHERE id = :id AND state IN ('PENDING', 'FIRED')")
    suspend fun setFiredIfPending(id: String): Int

    /** 生存中 (PENDING/FIRED) の行だけを DISMISSED にする。停止・スヌーズ済みの終端は上書きしない (更新行数を返す)。 */
    @Query("UPDATE scheduled_instances SET state = 'DISMISSED' WHERE id = :id AND state IN ('PENDING', 'FIRED')")
    suspend fun setDismissedIfLive(id: String): Int

    /**
     * 置き換えの終端化を1トランザクションで行う。
     * 先行だけ DISMISSED 化された状態でプロセスが死ぬと、firedWithinGrace が拾える
     * FIRED 行がどちらにも残らず (後発の AlarmManager 発火は消費済み)、鳴るべき
     * アラームが無鳴動で終わる。トランザクションが丸ごと戻った場合は先行が
     * FIRED のまま残るので、復旧は先行を再鳴動する (無鳴動より安全側)。
     */
    /** 鳴動中だった行 (FIRED) だけを鳴動待ち (PENDING) に戻す。停止・スヌーズ済みの終端は戻さない (更新行数を返す)。 */
    @Query("UPDATE scheduled_instances SET state = 'PENDING' WHERE id = :id AND state = 'FIRED'")
    suspend fun setPendingIfFired(id: String): Int

    /** 鳴ったが片付いていない行 (FIRED)。途中で途切れた鳴動の鳴らし直しに使う。 */
    @Query("SELECT * FROM scheduled_instances WHERE state = 'FIRED' AND triggerAtMillis > :since AND triggerAtMillis <= :now ORDER BY triggerAtMillis ASC")
    suspend fun firedBetween(since: Long, now: Long): List<ScheduledInstanceEntity>

    /** 鳴動猶予内の最新 FIRED 行。鳴動中プロセスキル後の再起動が拾う。 */
    @Query("SELECT * FROM scheduled_instances WHERE state = 'FIRED' AND triggerAtMillis > :since ORDER BY triggerAtMillis DESC LIMIT 1")
    suspend fun latestFiredSince(since: Long): ScheduledInstanceEntity?

    @Query("SELECT * FROM scheduled_instances ORDER BY triggerAtMillis ASC")
    fun allFlow(): Flow<List<ScheduledInstanceEntity>>

    /** 終了状態の行を発火時刻で掃除する (PENDING は触らない)。 */
    @Query("DELETE FROM scheduled_instances WHERE state != 'PENDING' AND triggerAtMillis < :before")
    suspend fun pruneTerminal(before: Long)

    /** 単発アラームの消費リセット用: 特定アラームの終端行だけを消す (PENDING は触らない)。 */
    @Query("DELETE FROM scheduled_instances WHERE standaloneAlarmId = :alarmId AND state != 'PENDING'")
    suspend fun deleteTerminalByAlarmId(alarmId: Long)
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

    /** 種類で絞ってから件数を切る (再同期などの記録に押し出されて鳴動の記録が消えないように) */
    @Query("SELECT * FROM audit_log WHERE action IN (:actions) ORDER BY atMillis DESC LIMIT :limit")
    fun recentByActionsFlow(actions: List<String>, limit: Int): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_log WHERE atMillis < :before")
    suspend fun prune(before: Long)
}
