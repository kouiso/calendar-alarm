package com.calendaralarm

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.shared.model.AccountSetting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiAccountSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = SettingsRepository(context)

    // JUnit は @Before に戻り値を許さないので、式本体にせずブロックで書く
    @Before
    fun clear() {
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    // DataStore は同じ JVM の他の試験クラスと共有される。アカウント設定を残すと再同期の試験が別の条件で走る
    @After
    fun clearAfter() {
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    @Test
    fun `旧版で1つだけ連携していたアカウントは複数連携の1件目として読める`() = runBlocking {
        context.settingsDataStore.edit { it[stringPreferencesKey("google_account_email")] = "old@example.com" }
        assertEquals(setOf("old@example.com"), repo.flow.first().googleAccountEmails)
    }

    @Test
    fun `2つ目を連携すると両方残り、旧キーは新しいキーへ移る`() = runBlocking {
        context.settingsDataStore.edit { it[stringPreferencesKey("google_account_email")] = "old@example.com" }
        repo.addGoogleAccount("new@example.com")
        assertEquals(setOf("old@example.com", "new@example.com"), repo.flow.first().googleAccountEmails)
        assertNull(context.settingsDataStore.data.first()[stringPreferencesKey("google_account_email")])
    }

    @Test
    fun `1つだけ解除しても他の連携は残る`() = runBlocking {
        repo.addGoogleAccount("a@example.com")
        repo.addGoogleAccount("b@example.com")
        repo.removeGoogleAccount("A@example.com")
        assertEquals(setOf("b@example.com"), repo.flow.first().googleAccountEmails)
    }

    @Test
    fun `大文字小文字だけ違うメールは同じアカウントとして1件だけ残る`() = runBlocking {
        repo.addGoogleAccount("a@example.com")
        repo.addGoogleAccount("A@Example.com")
        assertEquals(setOf("a@example.com"), repo.flow.first().googleAccountEmails)
        repo.removeGoogleAccount("A@EXAMPLE.COM")
        assertEquals(emptySet<String>(), repo.flow.first().googleAccountEmails)
    }

    @Test
    fun `アカウント設定はアカウントごとに保存され再起動後も残る`() = runBlocking {
        repo.setAccountSetting("a@example.com", AccountSetting(ring = false))
        repo.setAccountSetting("b@example.com", AccountSetting(minutesBefore = 15))
        val reread = SettingsRepository(context).flow.first().accountSettings
        assertEquals(AccountSetting(ring = false), reread["a@example.com"])
        assertEquals(15, reread["b@example.com"]?.minutesBefore)
    }

    @Test
    fun `壊れたアカウント設定は空として読み設定画面を落とさない`() = runBlocking {
        context.settingsDataStore.edit { it[stringPreferencesKey("account_settings")] = "{broken" }
        assertEquals(emptyMap<String, AccountSetting>(), repo.flow.first().accountSettings)
    }
}
