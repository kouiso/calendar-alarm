package com.calendaralarm.emu

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * PR #48 行13 の実機確認用 (コミットしない)。TalkBack のダブルタップが最終的に呼ぶ
 * AccessibilityNodeInfo.ACTION_CLICK を、UiAutomation (アクセシビリティ基盤) 経由で
 * 「スライドして停止」のノードへ送る。音は出ない。
 */
class A11yClickTest {
    private val tag = "A11yClickTest"
    private val desc = "スライドして停止"

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun findSlider(ui: UiAutomation): Pair<AccessibilityNodeInfo, String>? {
        for (w in ui.windows.orEmpty()) {
            val all = mutableListOf<AccessibilityNodeInfo>()
            collect(w.root, all)
            val hit = all.firstOrNull { it.contentDescription?.toString() == desc }
            if (hit != null) return hit to "window type=${w.type} layer=${w.layer} title=${w.title}"
        }
        return null
    }

    private fun status(key: String, value: String) {
        Log.i(tag, "$key $value")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
    }

    @Test
    fun accessibilityClickStopsAlarm() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val ctx = inst.targetContext
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "A11Y"
        ctx.sendBroadcast(
            Intent("com.calendaralarm.emu.SCHEDULE")
                .setComponent(ComponentName(ctx.packageName, "com.calendaralarm.emu.EmuHarnessReceiver"))
                .putExtra("seconds", 6)
                .putExtra("label", label),
        )
        status("scheduled", label)
        var found: Pair<AccessibilityNodeInfo, String>? = null
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 60_000 && found == null) {
            found = findSlider(ui)
            if (found == null) SystemClock.sleep(500)
        }
        assertNotNull("slider node not found in any window", found)
        val (node, where) = found!!
        val actions = node.actionList.joinToString { "${it.id}:${it.label}" }
        status(
            "FOUND",
            "after=${SystemClock.elapsedRealtime() - t0}ms $where class=${node.className} clickable=${node.isClickable} " +
                "focusable=${node.isFocusable} actions=[$actions]",
        )
        // 診断: 見つけたノードの親・兄弟・子と、それぞれが持つ操作 (id) を全部出す
        var root: AccessibilityNodeInfo = node
        while (root.parent != null) root = root.parent
        val sb = StringBuilder()
        fun dumpTree(n: AccessibilityNodeInfo, depth: Int) {
            val acts = n.actionList.joinToString(",") { it.id.toString() }
            sb.append("\n").append("  ".repeat(depth)).append("${n.className} text=${n.text} desc=${n.contentDescription} click=${n.isClickable} important=${n.isImportantForAccessibility} acts=[$acts]")
            for (i in 0 until n.childCount) n.getChild(i)?.let { dumpTree(it, depth + 1) }
        }
        dumpTree(root, 0)
        Log.i(tag, "TREE$sb")
        sb.toString().split("\n").filter { it.isNotBlank() }.forEachIndexed { i, l -> status("TREE%02d".format(i), l) }
        SystemClock.sleep(2_000)
        status("CLICKING", "now")
        // TalkBack が掴むのは、クリックできる一番近い祖先ノード (Compose はマージした親にクリックを持たせる)
        var target: AccessibilityNodeInfo? = node
        while (target != null && target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) target = target.parent
        status("TARGET", "clickable ancestor class=${target?.className} childCount=${target?.childCount} acts=${target?.actionList?.joinToString(",") { it.id.toString() }}")
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        status("ACTION_CLICK", "performAction returned $ok")
        SystemClock.sleep(4_000)
        val after = findSlider(ui)
        status("AFTER", "slider still present=${after != null}")
    }
}
