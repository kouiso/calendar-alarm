package com.calendaralarm

import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.engine.StopUiPermissions
import com.calendaralarm.engine.StopUiPlan
import com.calendaralarm.ui.OverlayPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun `オーバーレイ案内は更新ユーザーで未許可かつ未表示のときだけ出す`() {
        assertTrue(OverlayPrompt.shouldShow(onboarded = true, overlayGranted = false, alreadyShown = false))
        assertFalse(OverlayPrompt.shouldShow(onboarded = true, overlayGranted = false, alreadyShown = true))
        assertFalse(OverlayPrompt.shouldShow(onboarded = true, overlayGranted = true, alreadyShown = false))
        // 初回オンボーディング中は専用カードが案内するので出さない
        assertFalse(OverlayPrompt.shouldShow(onboarded = false, overlayGranted = false, alreadyShown = false))
    }

    @Test
    fun `オーバーレイ案内の表示済みは永続化され再表示されない`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.setOnboardingDone(true)
        assertFalse(settings.flow.first().overlayPromptShown)
        assertTrue(OverlayPrompt.shouldShow(true, false, settings.flow.first().overlayPromptShown))
        settings.setOverlayPromptShown(true)
        assertTrue(settings.flow.first().overlayPromptShown)
        assertFalse(OverlayPrompt.shouldShow(true, false, settings.flow.first().overlayPromptShown))
    }
}
