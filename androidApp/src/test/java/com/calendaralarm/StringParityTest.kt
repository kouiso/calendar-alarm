package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.engine.NotificationRuleTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 日本語 (values) と英語 (values-en) の文言が食い違わないこと (#105)。
 * 英語を入れ忘れると英語の端末で日本語のまま出て、差し込み記号 (%1$s) がずれると実行時に落ちる。
 */
class StringParityTest {

    private fun load(folder: String): Map<String, String> {
        val file = File("src/main/res/$folder/strings.xml")
        assertTrue("${file.absolutePath} が読めない", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    /** plurals は名前ごとに quantity → 文言。日本語は other だけ、英語は one と other を持つ */
    private fun loadPlurals(folder: String): Map<String, Map<String, String>> {
        val file = File("src/main/res/$folder/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.associate { p ->
            val items = p.getElementsByTagName("item")
            p.getAttribute("name") to (0 until items.length).map { items.item(it) as org.w3c.dom.Element }
                .associate { it.getAttribute("quantity") to it.textContent }
        }
    }

    private fun placeholders(text: String) = Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `日本語と英語は同じ名前の文言を持つ`() {
        val ja = load("values")
        val en = load("values-en")
        assertEquals("英語に無い文言", emptySet<String>(), ja.keys - en.keys)
        assertEquals("日本語に無い文言", emptySet<String>(), en.keys - ja.keys)
    }

    @Test
    fun `日本語と英語は同じ差し込み記号を持つ`() {
        val ja = load("values")
        val en = load("values-en")
        val bad = ja.keys.intersect(en.keys).filter { placeholders(ja.getValue(it)) != placeholders(en.getValue(it)) }
        assertEquals("差し込み記号が日英でずれている文言", emptyList<String>(), bad)
    }

    @Test
    fun `数で形を選ぶ文言 (plurals) も日英で同じ名前・同じ差し込み記号を持つ`() {
        val ja = loadPlurals("values")
        val en = loadPlurals("values-en")
        assertTrue("plurals が 1 つも読めない (読み方が壊れている)", ja.isNotEmpty())
        assertEquals("英語に無い plurals", emptySet<String>(), ja.keys - en.keys)
        assertEquals("日本語に無い plurals", emptySet<String>(), en.keys - ja.keys)
        // 日本語は数で形が変わらないので other だけでよい。英語は one と other の両方が要る
        assertEquals("日本語の other が無い plurals", emptyList<String>(), ja.filter { "other" !in it.value }.keys.sorted())
        assertEquals("英語の one か other が無い plurals", emptyList<String>(),
            en.filter { "one" !in it.value || "other" !in it.value }.keys.sorted())
        val bad = ja.keys.intersect(en.keys).flatMap { name ->
            val want = placeholders(ja.getValue(name).getValue("other"))
            // 英語の one は「Every day」のように数を書かないことがある (CountStringsTest)。書くなら同じ記号にする
            en.getValue(name).filter { (q, text) ->
                val got = placeholders(text)
                if (q == "one") !want.containsAll(got) else got != want
            }.map { "$name/${it.key}" }
        }
        assertEquals("差し込み記号が日英でずれている plurals", emptyList<String>(), bad)
        val kana = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")
        val kanaInEn = en.flatMap { (name, items) -> items.filter { kana.containsMatchIn(it.value) }.map { "$name/${it.key}" } }
        assertEquals("英語なのに日本語が残っている plurals", emptyList<String>(), kanaInEn)
    }

    @Test
    fun `英語の文言が日本語の字を含まない`() {
        val kana = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")
        val bad = load("values-en").filter { kana.containsMatchIn(it.value) }.keys.sorted()
        assertEquals("英語なのに日本語が残っている文言", emptyList<String>(), bad)
    }
}

/** 通知アラームのテンプレート名は端末の言語で出て、キーワードは日英どちらの通知にも当たるまま (#105) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRuleTemplatesLocalizationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun englishNamesHaveNoJapanese() {
        val kana = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")
        val names = NotificationRuleTemplates.all(context).map { it.name }
        assertEquals(12, names.size)
        assertTrue("英語の端末で日本語のテンプレート名が出る: $names", names.none { kana.containsMatchIn(it) })
        assertEquals("名前が重ならない", names.size, names.toSet().size)
        assertTrue(names.contains("Delivery notices"))
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseKeepsTheOriginalNames() {
        val names = NotificationRuleTemplates.all(context).map { it.name }
        assertTrue(names.contains("配達通知"))
        assertTrue(names.contains("Gmail 重要なメール"))
    }

    @Test
    fun keywordsStayBilingualInBothLanguages() {
        val delivery = NotificationRuleTemplates.all(context).first { it.anyKeywords.contains("out for delivery") }
        assertTrue("日本語の通知にも当たる", delivery.anyKeywords.contains("お届け"))
    }
}
