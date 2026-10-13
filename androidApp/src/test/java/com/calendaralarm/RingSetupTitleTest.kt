package com.calendaralarm

import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.ui.PermSnapshot
import com.calendaralarm.ui.ringSetupStage
import com.calendaralarm.ui.ringSetupTitle
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
class RingSetupTitleTest {

    private fun perms(notif: Boolean, exact: Boolean, fsi: Boolean, overlay: Boolean) =
        PermSnapshot(calendarOk = true, notifOk = notif, exactOk = exact, fsiOk = fsi, overlayOk = overlay)

    @Test
    fun `空白の件名は未指定として扱う`() {
        assertEquals("予定の10分前に、きちんと鳴らすために", ringSetupTitle("", 10))
        assertEquals("予定の10分前に、きちんと鳴らすために", ringSetupTitle("   ", 10))
        assertEquals("アラームを、きちんと鳴らすために", ringSetupTitle("  ", null))
    }

    @Test
    fun `段は鳴らす側→止める側→完了の順に決まる`() {
        assertEquals(1, ringSetupStage(perms(notif = false, exact = false, fsi = false, overlay = false)))
        assertEquals(1, ringSetupStage(perms(notif = true, exact = false, fsi = true, overlay = true)))
        assertEquals(2, ringSetupStage(perms(notif = true, exact = true, fsi = false, overlay = true)))
        assertEquals(2, ringSetupStage(perms(notif = true, exact = true, fsi = true, overlay = false)))
        assertEquals(3, ringSetupStage(perms(notif = true, exact = true, fsi = true, overlay = true)))
    }

    @Test
    fun `欠けている許可の数を数える`() {
        val p = perms(notif = false, exact = true, fsi = false, overlay = false)
        assertEquals(1, p.missingRingCount)
        assertEquals(2, p.missingStopCount)
    }

    @Test
    fun `旧オンボーディング済みの更新ユーザーは初回シートを出さない`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        assertFalse(settings.flow.first().ringSetupDone)
        settings.setOnboardingDone(true)
        assertTrue(settings.flow.first().ringSetupDone)
    }

    @Test
    fun `ringSetupDone を明示的に書いた値は移行の既定値より優先される`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.setOnboardingDone(true)
        settings.setRingSetupDone(false)
        assertFalse(settings.flow.first().ringSetupDone)
        settings.setRingSetupDone(true)
        assertTrue(settings.flow.first().ringSetupDone)
    }

    @Test
    fun `リード分があれば実際の分数を出す`() {
        assertEquals("会議の10分前に、きちんと鳴らすために", ringSetupTitle("会議", 10))
        assertEquals("会議の45分前に、きちんと鳴らすために", ringSetupTitle("会議", 45))
    }

    @Test
    fun `リード分が0以下やnullなら分数を出さない`() {
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", null))
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", 0))
        assertEquals("朝のアラームを、きちんと鳴らすために", ringSetupTitle("朝のアラーム", -5))
    }

    @Test
    fun `件名が無い時の既定文言`() {
        assertEquals("予定の15分前に、きちんと鳴らすために", ringSetupTitle(null, 15))
        assertEquals("アラームを、きちんと鳴らすために", ringSetupTitle(null, null))
    }
}
