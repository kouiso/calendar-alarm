package com.calendaralarm

import android.content.Intent
import com.calendaralarm.ai.ShareImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareImportTest {

    private fun send(type: String?, subject: String? = null, text: String? = null) =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

    @Test
    fun `件名と本文をまとめて取り込みに渡す`() {
        val text = ShareImport.textFrom(send("text/plain", "打ち合わせ", "10/22 15:00 から本社で"))
        assertEquals("打ち合わせ\n10/22 15:00 から本社で", text)
    }

    @Test
    fun `件名と本文が同じなら1回だけにする`() {
        assertEquals("会議", ShareImport.textFrom(send("text/plain", "会議", "会議")))
    }

    @Test
    fun `テキスト以外の共有と空の共有と通常起動は取り込まない`() {
        assertNull(ShareImport.textFrom(send("image/png", text = "x")))
        assertNull(ShareImport.textFrom(send("text/plain", "  ", "  ")))
        assertNull(ShareImport.textFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(ShareImport.textFrom(null))
    }

    @Test
    fun `長い本文は抽出の上限で切る`() {
        assertEquals(6000, ShareImport.textFrom(send("text/plain", text = "あ".repeat(7000)))!!.length)
    }

    @Test
    fun `AIで読めない時の下書きは最初の行をタイトルにして日時は空にする`() {
        val d = ShareImport.draftFrom("\n  歯医者の予約  \n10月22日 15時")
        assertEquals("歯医者の予約", d.title)
        assertEquals("", d.start)
    }
}
