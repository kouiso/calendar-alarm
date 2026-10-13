package com.calendaralarm

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.NotificationAlarmService
import com.calendaralarm.engine.RingingActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowKeyguardManager

/**
 * ロック中の鳴動画面の回帰テスト。
 * PIN ロック中にキーガード解除を要求すると、鳴動画面の上に PIN 入力 (bouncer) が
 * 重なって停止ボタンが見えなくなる (エミュレータ E2E の locked_screen_on で実測)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RingingActivityLockscreenTest.CountingKeyguardManager::class])
class RingingActivityLockscreenTest {

    /** requestDismissKeyguard の呼び出し回数を数えるだけのキーガード。 */
    @Implements(KeyguardManager::class)
    class CountingKeyguardManager : ShadowKeyguardManager() {
        @Implementation
        override fun requestDismissKeyguard(
            activity: Activity,
            callback: KeyguardManager.KeyguardDismissCallback?,
        ) {
            dismissRequests++
            lastCallback = callback
        }

        companion object {
            var dismissRequests = 0
            var lastCallback: KeyguardManager.KeyguardDismissCallback? = null
        }
    }

    private lateinit var app: CalendarAlarmApp

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        CountingKeyguardManager.dismissRequests = 0
        CountingKeyguardManager.lastCallback = null
        val km = app.getSystemService(KeyguardManager::class.java)
        Shadow.extract<CountingKeyguardManager>(km).apply {
            setKeyguardLocked(true)
            setIsKeyguardSecure(true)
            setIsDeviceSecure(true)
        }
    }

    @After
    fun tearDown() {
        ShadowKeyguardManager.reset()
        NotificationAlarmService.pendingOpens.clear()
    }

    @Test
    fun `PINロック中の鳴動画面はロックの上に出てキーガード解除を要求しない`() {
        val controller = Robolectric.buildActivity(
            RingingActivity::class.java,
            RingingActivity.intent(app, "tm:lock"),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertTrue("ロック画面の上に表示すること", shadowOf(activity).showWhenLocked)
        assertTrue("消灯中なら画面を点けること", shadowOf(activity).turnScreenOn)
        assertEquals(
            "PIN 入力が鳴動画面 (停止ボタン) を覆わないこと",
            0,
            CountingKeyguardManager.dismissRequests,
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `ロック中に通知元を開くとキーガード解除を求め解除後に元アプリを開いて止める`() {
        val source = PendingIntent.getActivity(
            app, 0, Intent("com.example.OPEN_SOURCE"), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationAlarmService.pendingOpens["nt:lock"] = source
        val controller = Robolectric.buildActivity(
            RingingActivity::class.java,
            RingingActivity.intent(app, "nt:lock"),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        RingingActivity::class.java.getDeclaredMethod("openSourcePendingIntent")
            .apply { isAccessible = true }.invoke(activity)

        assertEquals("通知元を開く操作に限りキーガード解除を求めること", 1, CountingKeyguardManager.dismissRequests)
        assertFalse("解除前に鳴動画面を閉じないこと (閉じると解除要求が取り消される)", activity.isFinishing)
        assertEquals(null, startedDismiss())

        CountingKeyguardManager.lastCallback!!.onDismissSucceeded()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "解除後に元アプリを開くこと",
            "com.example.OPEN_SOURCE",
            shadowOf(app).nextStartedActivity?.action,
        )
        assertNotNull("解除後に鳴動を止めること", startedDismiss())
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    private fun startedDismiss(): Intent? {
        val shadowApp = shadowOf(app)
        return generateSequence { shadowApp.nextStartedService }
            .firstOrNull { it.action == AlarmService.ACTION_DISMISS }
    }
}
