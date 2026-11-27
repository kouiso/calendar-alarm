package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.NotificationContentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通知アラームの内容の印の計算と保存 (#384) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationContentStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric は同じプロセスで保存を持ち越すので空にする
        context.getSharedPreferences("notification_alarm_content", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `keyOf は題と本文が同じなら同じ印を返し違えば違う印を返す`() {
        val a = NotificationContentStore.keyOf("a", "b")
        assertEquals(a, NotificationContentStore.keyOf("a", "b"))
        assertNotEquals(a, NotificationContentStore.keyOf("a", "c"))
        // 区切りがあるので、つなぎ目がずれても同じ印にならない
        assertNotEquals(a, NotificationContentStore.keyOf("ab", ""))
        assertEquals(64, a.length)
    }

    @Test
    fun `put した印を新しい Store で読める`() {
        NotificationContentStore(context).put("nf:x", "KEY", 1_000L)
        assertEquals("KEY", NotificationContentStore(context).get("nf:x"))
        assertNull(NotificationContentStore(context).get("nf:none"))
    }

    @Test
    fun `値の形が壊れていれば null を返す`() {
        context.getSharedPreferences("notification_alarm_content", Context.MODE_PRIVATE)
            .edit().putString("nf:bad", "no-separator").commit()
        assertNull(NotificationContentStore(context).get("nf:bad"))
    }

    @Test
    fun `256 件を超えたら古い順に消す`() {
        val store = NotificationContentStore(context)
        val total = NotificationContentStore.MAX_ENTRIES + 1
        for (i in 1..total) store.put("nf:$i", "K$i", i.toLong())
        assertNull(store.get("nf:1"))
        assertEquals("K2", store.get("nf:2"))
        assertEquals("K$total", store.get("nf:$total"))
    }
}
