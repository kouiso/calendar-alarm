package com.calendaralarm

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.withTransaction
import com.calendaralarm.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "RestoreReset"

/**
 * バックアップから復元された事実の印 (#269)。
 *
 * DB は 1 ファイル (calendar-alarm.db) なので、表ごとにバックアップから外すことはできない。
 * そこで「復元された」ことを noBackupFilesDir (バックアップ対象外) の印で覚え、
 * 次の起動で予約の行と予定ごとの指定を捨てる。
 * 印は復元の完了通知 (BackupAgent.onRestoreFinished) だけが置く。
 * 「印が無く DB に行がある」で判定すると、この版へ更新しただけの端末でも行を捨ててしまう。
 */
object RestoreMarker {
    private const val NAME = "restored-from-backup"

    private fun file(context: Context) = File(context.noBackupFilesDir, NAME)

    fun mark(context: Context) {
        file(context).apply { parentFile?.mkdirs() }.writeText("1")
    }

    fun isPending(context: Context): Boolean = file(context).exists()

    fun consume(context: Context) {
        val f = file(context)
        // 消せないまま黙ると、毎回の起動で 2 表を消し続ける。せめて記録に残す
        if (!f.delete() && f.exists()) Log.w(TAG, "復元の印を消せない: ${f.path}")
    }
}

object RestoreReset {
    /**
     * 復元の印があれば、予約の行 (scheduled_instances) と予定ごとの指定 (event_overrides) を捨て、
     * [resync] を呼ぶ。印が無ければ何も触らない。捨てた時 true。
     * カレンダーごとの設定 (calendar_prefs) は消さず、行番号の控えを空にして旧形式の行だけ消す (#268)。
     * 印は行を捨てた後で消す。途中で落ちても次の起動でやり直せる (消すのは冪等)。
     */
    suspend fun runIfRestored(
        context: Context,
        db: AppDatabase,
        onCleared: () -> Unit = {},
        resync: suspend () -> Unit,
    ): Boolean {
        // 起動時の resync を待たせる門は、削除と印の消費だけで開ける。
        // resync 自身の完了まで待たせると、ここから呼ぶ resync が自分を待って固まる
        try {
            if (!RestoreMarker.isPending(context)) return false
            // 2 表を 1 つのトランザクションで消し、片方だけ消えた途中の状態を他から見せない
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    db.scheduledInstances().deleteAll()
                    db.eventOverrides().deleteAll()
                    // カレンダーごとの設定の行番号の控えは別の端末の物。旧形式の行は別の端末では結び付けられない (#268)
                    db.calendarPrefs().deleteLegacy()
                    db.calendarPrefs().clearCalendarIds()
                }
            }
            RestoreMarker.consume(context)
        } finally {
            onCleared()
        }
        resync()
        return true
    }

    /**
     * 起動時に [runIfRestored] を [scope] で走らせ、削除が済んだら [gate] を開ける。
     * 失敗しても取り消されても [gate] は必ず開ける。開かないと以後の resync が全部止まる
     */
    fun launchAtStart(
        scope: CoroutineScope,
        context: Context,
        db: AppDatabase,
        gate: CompletableDeferred<Unit>,
        resync: suspend () -> Unit,
    ): Job = scope.launch {
        try {
            runIfRestored(context = context, db = db, onCleared = { gate.complete(Unit) }, resync = resync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "復元の後始末に失敗", e)
        } finally {
            gate.complete(Unit)
        }
    }
}

/**
 * 自動バックアップの復元が終わった時に印を置く (#269)。
 * データの保存・復元そのものは既定の全体バックアップ (fullBackupOnly) に任せる。
 */
open class RestoreMarkerAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?,
    ) = Unit

    public override fun onRestoreFinished() {
        super.onRestoreFinished()
        // 置けないと復元されなかった扱いになり、古い行と指定が残る。黙らずに記録に残す
        runCatching { RestoreMarker.mark(this) }.onFailure { Log.w(TAG, "復元の印を置けない", it) }
    }
}
