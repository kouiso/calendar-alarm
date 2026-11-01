package com.calendaralarm

import com.calendaralarm.engine.StopUiPermissions
import com.calendaralarm.engine.StopUiPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopUiPlanTest {

    @Test
    fun `オーバーレイはFSIの可否に関係なく許可があれば出す`() {
        assertTrue(StopUiPlan.of(StopUiPermissions(true, fsiOk = true, overlayOk = true)).showOverlay)
        assertTrue(StopUiPlan.of(StopUiPermissions(true, fsiOk = false, overlayOk = true)).showOverlay)
        assertFalse(StopUiPlan.of(StopUiPermissions(true, fsiOk = true, overlayOk = false)).showOverlay)
    }

    @Test
    fun `全経路が使えるときは警告なし`() {
        assertEquals(emptyList<String>(), StopUiPlan.of(StopUiPermissions(true, true, true)).warnings)
    }

    @Test
    fun `劣化状態はそれぞれ警告になる`() {
        val ovDenied = StopUiPlan.of(StopUiPermissions(true, fsiOk = true, overlayOk = false)).warnings
        assertEquals(1, ovDenied.size)
        assertTrue("オーバーレイ未許可" in ovDenied[0])

        val fsiDenied = StopUiPlan.of(StopUiPermissions(true, fsiOk = false, overlayOk = true)).warnings
        assertEquals(1, fsiDenied.size)
        assertTrue("FSI拒否" in fsiDenied[0])

        val all = StopUiPlan.of(StopUiPermissions(false, fsiOk = false, overlayOk = false)).warnings
        assertEquals(3, all.size)
        assertTrue(all.any { "通知OFF" in it })
    }

    @Test
    fun `フルスクリーン強制OFFならオーバーレイも出さず理由を記録する`() {
        val off = StopUiPlan.of(StopUiPermissions(true, fsiOk = true, overlayOk = true), forceFullScreen = false)
        assertFalse(off.showOverlay)
        assertTrue(off.warnings.any { it.startsWith("フルスクリーン強制OFF") })
        // 既定 (ON) は従来どおり
        assertTrue(StopUiPlan.of(StopUiPermissions(true, fsiOk = true, overlayOk = true)).showOverlay)
    }

    @Test
    fun `フルスクリーン強制OFFでも通知が出せない時はオーバーレイを残す`() {
        // 通知 OFF + 強制 OFF でオーバーレイまで消すと、画面上に停止手段が何も無くなる
        val p = StopUiPermissions(notificationsOk = false, fsiOk = true, overlayOk = true)
        assertTrue(StopUiPlan.of(p, forceFullScreen = false).showOverlay)
    }
}
