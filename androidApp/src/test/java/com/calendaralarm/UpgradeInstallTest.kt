package com.calendaralarm

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.isUpgradeInstall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 招待フィルタの移行 (#155) で使う「前の版から更新して入ったか」の判定。
 * 本番の判定 (Context.isUpgradeInstall) を、端末の導入時刻・更新時刻を差し替えて確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeInstallTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun install(first: Long, last: Long) {
        val info = PackageInfo().apply {
            packageName = context.packageName
            firstInstallTime = first
            lastUpdateTime = last
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `新しく入れた端末は更新扱いにしない`() {
        install(first = 1_000L, last = 1_000L)
        assertFalse(context.isUpgradeInstall())
    }

    @Test
    fun `前の版から更新した端末は更新扱いにする`() {
        install(first = 1_000L, last = 2_000L)
        assertTrue(context.isUpgradeInstall())
    }
}
