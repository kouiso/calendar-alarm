package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.NotificationContentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

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

    /**
     * 同じプロセスの中で、別の Store インスタンスから読めることだけを確かめる
     * (SharedPreferences のプロセス内キャッシュを共有するため)。プロセスをまたいだ永続化は確かめていない
     */
    @Test
    fun `put した印を同じプロセスの新しい Store で読める`() {
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

    /**
     * 印の保存ファイル (`<prefs 名>.xml`) を自動バックアップと端末間移行から外していること。
     * 塩なしの SHA-256 は 6 桁コードなどを引き戻せるため、端末の外へ出さない。
     * 除外だけを書き (include は無い)、他の保存は今までどおりバックアップされる
     */
    @Test
    fun `印の保存ファイルは全てのバックアップ規則で除外される`() {
        fun excludes(resId: Int): Map<String, List<String>> {
            val out = mutableMapOf<String, MutableList<String>>()
            var section = ""
            val parser = context.resources.getXml(resId)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "full-backup-content", "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> {
                        assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
                        out.getOrPut(section) { mutableListOf() }.add(parser.getAttributeValue(null, "path"))
                    }
                    "include" -> throw AssertionError("include を足すと他の保存が変わる: $section")
                }
            }
            return out
        }

        val target = "notification_alarm_content.xml"
        assertEquals(listOf(target), excludes(R.xml.backup_rules)["full-backup-content"])
        val extraction = excludes(R.xml.data_extraction_rules)
        assertEquals(listOf(target), extraction["cloud-backup"])
        assertEquals(listOf(target), extraction["device-transfer"])
        // 規則が Manifest から参照されている (参照が外れると規則は効かない)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
