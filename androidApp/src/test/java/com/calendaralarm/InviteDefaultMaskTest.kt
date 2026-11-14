package com.calendaralarm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 招待フィルタの既定 (#155)。保存キーが無い人の表示と初回トグルの起点が、
 * 共有モデルの既定「承諾した招待のみ」と食い違わないことを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteDefaultMaskTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
        settings = SettingsRepository(context)
    }

    @Test
    fun `未保存の人の既定は共有モデルと同じ承諾のみ`() = runBlocking {
        assertEquals(InviteFilter(), settings.flow.first().inviteFilter)
        assertEquals(
            InviteFilter(accepted = true, tentative = false, needsAction = false, declined = false),
            settings.flow.first().inviteFilter,
        )
    }

    @Test
    fun `未保存から仮承諾を1回押すと承諾と仮承諾だけになり未回答はONにならない`() = runBlocking {
        settings.toggleInviteStatus(InviteStatus.TENTATIVE)
        assertEquals(
            InviteFilter(accepted = true, tentative = true, needsAction = false, declined = false),
            settings.flow.first().inviteFilter,
        )
    }

    @Test
    fun `保存済みの選択は既定変更の影響を受けない`() = runBlocking {
        settings.setInviteFilter(InviteFilter(accepted = true, tentative = true, needsAction = true, declined = false))
        assertEquals(
            InviteFilter(accepted = true, tentative = true, needsAction = true, declined = false),
            settings.flow.first().inviteFilter,
        )
    }
}
