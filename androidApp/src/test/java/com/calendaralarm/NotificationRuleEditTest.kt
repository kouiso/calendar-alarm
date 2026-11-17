package com.calendaralarm

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.engine.NotificationRuleTemplates
import com.calendaralarm.engine.matches
import com.calendaralarm.shared.model.NotificationRuleSpec
import com.calendaralarm.ui.NotificationRulesCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * #226「通知アラームのルールを入れた後に直せるようにする」の確かめ方。
 * 実際の設定画面と同じく flow → NotificationRulesCard に渡して描き、
 * 行タップ → ダイアログ → 保存/削除 が DataStore に届くまで見る。
 *
 * Robolectric では AlertDialog (プラットフォームの別窓) の中身がテストの
 * semantics ツリーに出ず、さらにダイアログ表示中は waitForIdle が戻らない。
 * そのためダイアログの中身は WindowManagerGlobal.mViews → AndroidComposeView の
 * semanticsOwner を直接たどって読む (実際の AlertDialog を開いたまま確かめる)。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class NotificationRuleEditTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher(scheduler))

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(app)

    private val meeting = NotificationRuleSpec(
        name = "平日の会議",
        packageName = "com.example.mail",
        requiredKeywords = listOf("会議"),
    )

    @Before
    @After
    fun clear() {
        runBlocking { app.settingsDataStore.edit { it.clear() } }
    }

    private fun seed(vararg rules: NotificationRuleSpec) = runBlocking {
        settings.setNotificationRules(rules.toList())
    }

    private fun show() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                val prefs by settings.flow.collectAsState(initial = null)
                prefs?.let { NotificationRulesCard(settings, it) }
            }
        }
        // カードが出るまで待つ (「＋ルール」は常に出る)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("＋ルール").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rules() = runBlocking {
        SettingsRepository(app).flow.first().notificationRules
    }

    /**
     * waitForIdle はプラットフォーム窓のダイアログが出ている間ずっと戻らないため
     * (ダイアログの描画がフレームを投げ続ける)、テストスケジューラとメイン looper、
     * compose のフレーム時計を手で回して状態を進める。
     */
    private fun pump() {
        repeat(3) {
            scheduler.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitRules(desc: String, cond: (List<NotificationRuleSpec>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (cond(rules())) return
            Thread.sleep(50)
        }
        assertTrue("時限切れ: $desc (rules=${rules()})", cond(rules()))
    }

    // --- ダイアログ窓の semantics を直接たどる道具 ---

    @Suppress("UNCHECKED_CAST")
    private fun windowViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getInstance").invoke(null)
        return ReflectionHelpers.getField<ArrayList<View>>(wmg, "mViews") ?: emptyList()
    }

    private fun semanticsRootsOf(view: View): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(v: View) {
            if (v.javaClass.methods.any { it.name == "getSemanticsOwner" }) {
                val owner = v.javaClass.getMethod("getSemanticsOwner").invoke(v)
                val root = owner.javaClass.getMethod("getRootSemanticsNode").invoke(owner)
                if (root is SemanticsNode) out += root
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }

    /** ダイアログ側の semantics ノードを全部拾う (IsDialog の印はルートの子につく) */
    private fun dialogNodes(): List<SemanticsNode> {
        fun gather(n: SemanticsNode, out: MutableList<SemanticsNode>) {
            out += n
            n.children.forEach { gather(it, out) }
        }
        return windowViews().flatMap { semanticsRootsOf(it) }
            .flatMap { root ->
                val out = mutableListOf<SemanticsNode>()
                gather(root, out)
                out
            }
            .filter { tree -> tree.config.contains(SemanticsProperties.IsDialog) }
            .let { dialogRoots ->
                // IsDialog を持つルート配下のノードだけに絞る
                dialogRoots.flatMap { root ->
                    val out = mutableListOf<SemanticsNode>()
                    gather(root, out)
                    out
                }
            }
    }

    // この compose 版の SemanticsConfiguration に getOrNull が無いので小さく用意する
    private fun <T> SemanticsConfiguration.getOrNullCompat(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) this[key] else null

    private fun nodeText(n: SemanticsNode): String {
        val editable = n.config.getOrNullCompat(SemanticsProperties.EditableText)?.text
        val text = n.config.getOrNullCompat(SemanticsProperties.Text)
            ?.joinToString("\n") { it.text }
        return editable ?: text ?: ""
    }

    private fun describeView(v: View, depth: Int): String {
        val has = v.javaClass.methods.any { it.name == "getSemanticsOwner" }
        var s = "  ".repeat(depth) + v.javaClass.simpleName + "(hasOwner=$has)"
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) s += "\n" + describeView(v.getChildAt(i), depth + 1)
        }
        return s
    }

    private fun findDialogNode(desc: String, pred: (SemanticsNode) -> Boolean): SemanticsNode =
        dialogNodes().firstOrNull(pred)
            ?: error(
                "ダイアログ内に見つからない: $desc\n" +
                    windowViews().joinToString("\n") { describeView(it, 0) } +
                    "\nnodes=${dialogNodes().map { nodeText(it) }}",
            )

    /** ダイアログの semantics ノード上のアクションを直接発火する (performClick は waitForIdle が詰まるので使えない) */
    private fun clickDialogText(text: String) {
        val node = findDialogNode(text) { nodeText(it) == text }
        val action = node.config.getOrNullCompat(SemanticsActions.OnClick)?.action
            ?: error("「$text」に OnClick が無い")
        @Suppress("UNCHECKED_CAST")
        (action as () -> Boolean).invoke()
        pump()
    }

    /** 名前の欄はダイアログの先頭の入力欄 (SetText を持つ最初のノード) */
    private fun setDialogText(label: String, text: String) {
        val node = findDialogNode(label) { it.config.contains(SemanticsActions.SetText) }
        val action = node.config.getOrNullCompat(SemanticsActions.SetText)?.action
            ?: error("「$label」の欄に SetText が無い")
        @Suppress("UNCHECKED_CAST")
        (action as (AnnotatedString) -> Boolean).invoke(AnnotatedString(text))
        pump()
    }

    @Test
    fun `ルールの行を押すと中身が入った状態でダイアログが開く`() {
        seed(meeting)
        show()
        compose.onNodeWithText("平日の会議").performClick()
        pump()

        // ダイアログが開き、名前の欄に今の名前が入っている。編集用の「保存」「削除」が出る
        findDialogNode("ダイアログ") { it.config.contains(SemanticsProperties.IsDialog) }
        findDialogNode("名前の欄") {
            it.config.getOrNullCompat(SemanticsProperties.EditableText)?.text == "平日の会議"
        }
        findDialogNode("保存") { nodeText(it) == "保存" }
        findDialogNode("削除") { nodeText(it) == "削除" }
    }

    @Test
    fun `「保存」で同じルールが上書きされる。行は増えない`() {
        seed(meeting)
        show()
        compose.onNodeWithText("平日の会議").performClick()
        pump()

        setDialogText("名前の欄", "夜間の会議")
        clickDialogText("保存")

        waitRules("上書き保存が保存先に届くこと") { rules ->
            rules.size == 1 && rules[0].name == "夜間の会議" && rules[0].packageName == "com.example.mail"
        }
    }

    @Test
    fun `ダイアログから削除できる`() {
        seed(meeting)
        show()
        compose.onNodeWithText("平日の会議").performClick()
        pump()

        clickDialogText("削除")
        waitRules("削除が保存先に届くこと") { it.isEmpty() }
    }

    @Test
    fun `新しく足す時は末尾に足される`() {
        seed(meeting)
        show()
        compose.onNodeWithText("＋ルール").performClick()
        pump()

        // 追加ダイアログには「削除」が無く「追加」がある
        findDialogNode("ダイアログ") { it.config.contains(SemanticsProperties.IsDialog) }
        findDialogNode("追加") { nodeText(it) == "追加" }
        assertTrue("追加ダイアログに削除は出ない", dialogNodes().none { nodeText(it) == "削除" })

        setDialogText("名前の欄", "新しいルール")
        clickDialogText("追加")

        waitRules("追加が末尾に足されること") { rules ->
            rules.size == 2 && rules[0].name == "平日の会議" && rules[1].name == "新しいルール"
        }
    }

    @Test
    fun `型の名前を押して入れたルールも同じように直せる`() {
        seed()
        show()
        val templateName = NotificationRuleTemplates.all(app).first().name
        compose.onNodeWithText(templateName).performClick()
        waitRules("型のルールが一覧に足されること") { it.any { r -> r.name == templateName } }

        // 一覧に出るまで待ってから行を押す
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(templateName).fetchSemanticsNodes().size >= 2
        }
        compose.onAllNodesWithText(templateName)[0].performClick()
        pump()

        // 足された行を押すと編集ダイアログが開く
        findDialogNode("ダイアログ") { it.config.contains(SemanticsProperties.IsDialog) }
        findDialogNode("削除") { nodeText(it) == "削除" }
    }

    @Test
    fun `直して保存した条件が次の通知の判定に使われる`() = runBlocking {
        // 直して保存する → 保存した物を読み直す → 通知が当たる・外れるを判定する、の順に通す (#226)
        settings.setNotificationRules(listOf(meeting))
        val before = SettingsRepository(app).flow.first().notificationRules.single()
        assertTrue(before.matches("com.example.mail", "明日の会議のお知らせ"))

        val edited = before.copy(requiredKeywords = listOf("招集"))
        settings.setNotificationRules(listOf(edited))
        val after = SettingsRepository(app).flow.first().notificationRules.single()
        assertEquals("招集", after.requiredKeywords.single())
        assertTrue(after.matches("com.example.mail", "招集のお知らせ"))
        assertFalse(after.matches("com.example.mail", "明日の会議のお知らせ"))
    }
}
