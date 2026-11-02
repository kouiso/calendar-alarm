package com.calendaralarm

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DefaultSoundSelectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = SettingsRepository(context)
    private val uri = Uri.parse("content://documents/audio/selected")

    @Before
    @After
    fun clear() = runBlocking { context.settingsDataStore.edit { it.clear() }; Unit }

    @Test
    fun storesOnlyAfterPermissionWasGrantedAndKeepsTheSavedChoice() = runBlocking {
        var granted: Uri? = null
        assertTrue(repo.selectDefaultSound(uri) { granted = it })
        assertEquals(uri, granted)
        assertEquals(uri.toString(), SettingsRepository(context).flow.first().defaultSoundUri)
    }

    @Test
    fun deniedPermissionDoesNotReplaceThePreviousSound() = runBlocking {
        repo.setDefaultSoundUri("content://documents/audio/previous")
        assertFalse(repo.selectDefaultSound(uri) { throw SecurityException("No persistable grant") })
        assertEquals("content://documents/audio/previous", repo.flow.first().defaultSoundUri)
    }

    @Test
    fun unsupportedProviderDoesNotChangeTheDefaultToCustom() = runBlocking {
        assertFalse(repo.selectDefaultSound(uri) { throw UnsupportedOperationException() })
        assertNull(repo.flow.first().defaultSoundUri)
    }
}
