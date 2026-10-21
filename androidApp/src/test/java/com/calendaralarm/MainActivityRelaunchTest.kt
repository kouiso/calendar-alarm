package com.calendaralarm

import android.content.Intent
import android.os.Bundle
import com.calendaralarm.ai.ShareImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 共有→予定 (#102) の取り込みが二重に出ない回帰テスト。
 * プロセスが落ちた後の作り直しでは、OS は最初の SEND intent をそのまま渡し直す。
 * 閉じた取り込みがもう一度出ると、追加を押した時に予定とアラームが二重になる (エミュレータで再現済み)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRelaunchTest {

    private val shared = "歯医者の予約 10月23日 15時から"

    /** OS が保持している最初の共有 intent。作り直しのたびに新しい複製が渡る */
    private fun originalShare() = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shared)
    }

    /** 取り込みを閉じた後に退避された状態 (共有の本文は含まない) */
    private fun savedAfterDismiss(): Bundle {
        val first = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.destroy()
        return saved
    }

    @Test
    fun `新しく開いた共有は取り込む`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, originalShare()).create().get()
        assertEquals(shared, activity.pendingSharedText)
    }

    @Test
    fun `閉じた後にプロセスが落ちて作り直されても最初の共有を読み直さない`() {
        val saved = savedAfterDismiss()
        val activity = Robolectric.buildActivity(MainActivity::class.java, originalShare()).create(saved).get()
        assertNull(activity.pendingSharedText)
    }

    @Test
    fun `読み取り中に作り直されたら退避した本文から1回だけ戻す`() {
        val first = Robolectric.buildActivity(MainActivity::class.java, originalShare()).create()
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.destroy()

        val again = Robolectric.buildActivity(MainActivity::class.java, originalShare()).create(saved).get()
        assertEquals(shared, again.pendingSharedText)
    }

    @Test
    fun `履歴から開き直した時は最初の共有を読み直さない`() {
        val fromRecents = originalShare().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val activity = Robolectric.buildActivity(MainActivity::class.java, fromRecents).create().get()
        assertNull(activity.pendingSharedText)
    }

    @Test
    fun `メール→予定の抽出結果も作り直しで読み直さない`() {
        val saved = savedAfterDismiss()
        val fromNotification = Intent(Intent.ACTION_MAIN).apply {
            putExtra(MainActivity.EXTRA_EXTRACTED_EVENT, """{"title":"会議","start":"2026-10-23T15:00"}""")
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java, fromNotification).create(saved).get()
        assertNull(activity.pendingExtractedJson)
    }

    @Test
    fun `新しく開いた時だけ intent を読む判定`() {
        assertTrue(ShareImport.isFreshLaunch(null, originalShare()))
        assertTrue(ShareImport.isFreshLaunch(null, null))
        assertFalse(ShareImport.isFreshLaunch(Bundle(), originalShare()))
        assertFalse(
            ShareImport.isFreshLaunch(null, originalShare().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)),
        )
    }
}
