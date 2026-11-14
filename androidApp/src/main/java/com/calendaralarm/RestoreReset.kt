package com.calendaralarm

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import com.calendaralarm.data.db.AppDatabase
import java.io.File

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
        file(context).delete()
    }
}

object RestoreReset {
    /**
     * 復元の印があれば、予約の行 (scheduled_instances) と予定ごとの指定 (event_overrides) を捨て、
     * [resync] を呼ぶ。印が無ければ何も触らない。捨てた時 true。
     * 印は行を捨てた後で消す。途中で落ちても次の起動でやり直せる (消すのは冪等)。
     */
    suspend fun runIfRestored(context: Context, db: AppDatabase, resync: suspend () -> Unit): Boolean {
        if (!RestoreMarker.isPending(context)) return false
        db.scheduledInstances().deleteAll()
        db.eventOverrides().deleteAll()
        RestoreMarker.consume(context)
        resync()
        return true
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
        runCatching { RestoreMarker.mark(this) }
    }
}
