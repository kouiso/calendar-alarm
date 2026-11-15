package com.calendaralarm

import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.ui.CheckState
import com.calendaralarm.ui.TroubleshootChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 検査画面の「おやすみモード」と「おやすみモードの例外の許可」の行 (#149)。
 * 鳴動側の決まり (ON は許可が無くても OS に任せて鳴らす。止めるのは利用者の OFF だけ) と行の状態がずれないことを固める。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DndTroubleshootRowTest {

    private lateinit var app: CalendarAlarmApp
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(nm).setConsolidatedNotificationPolicy(
            NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0),
        )
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
    }

    private fun row(key: String, ignoreDnd: Boolean) =
        TroubleshootChecks.collect(app, vibrate = true, ignoreDnd = ignoreDnd).first { it.key == key }

    @Test
    fun `ONで許可が無くてもOSがアラームを通すならおやすみモードの行はOK`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val dnd = row("dnd", ignoreDnd = true)
        assertEquals(CheckState.OK, dnd.state)
        assertEquals(app.getString(R.string.trouble_dnd_ok), dnd.title)
    }

    @Test
    fun `ONでOSがアラームを止めるサイレントなら要対応でおやすみモードの設定へ飛ぶ`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val dnd = row("dnd", ignoreDnd = true)
        assertEquals(CheckState.NG, dnd.state)
        assertEquals(app.getString(R.string.trouble_dnd_ng), dnd.title)
        assertEquals("android.settings.ZEN_MODE_SETTINGS", dnd.fix?.action)
    }

    @Test
    fun `利用者がOFFにしていておやすみモード中は参考で飛び先なし`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val dnd = row("dnd", ignoreDnd = false)
        assertEquals(CheckState.INFO, dnd.state)
        assertEquals(app.getString(R.string.trouble_dnd_blocked), dnd.title)
        assertEquals(app.getString(R.string.trouble_dnd_blocked_desc), dnd.description)
        assertNull(dnd.fix)
    }

    @Test
    fun `利用者がOFFにしていておやすみモードでなければOK`() {
        val dnd = row("dnd", ignoreDnd = false)
        assertEquals(CheckState.OK, dnd.state)
        assertEquals(app.getString(R.string.trouble_dnd_off), dnd.title)
        assertNull(dnd.fix)
    }

    @Test
    fun `許可の行はONで未許可なら参考で許可の画面へ飛ぶ`() {
        val policy = row("dnd_policy", ignoreDnd = true)
        assertEquals(CheckState.INFO, policy.state)
        assertEquals(app.getString(R.string.trouble_dnd_policy_ng), policy.title)
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, policy.fix?.action)
    }

    @Test
    fun `許可の行はONで許可済みならOK、OFFなら対象外`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        assertEquals(CheckState.OK, row("dnd_policy", ignoreDnd = true).state)
        assertEquals(CheckState.NA, row("dnd_policy", ignoreDnd = false).state)
    }
}
