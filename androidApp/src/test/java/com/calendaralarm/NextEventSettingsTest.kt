package com.calendaralarm

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NextEventSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = SettingsRepository(context)

    @Before
    @After
    fun clear() {
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    @Test
    fun defaultsToVisibleAndKeepsTheSavedChoice() = runBlocking {
        assertTrue(repo.flow.first().showNextEvent)
        repo.setShowNextEvent(false)
        assertFalse(SettingsRepository(context).flow.first().showNextEvent)
        repo.setShowNextEvent(true)
        assertTrue(SettingsRepository(context).flow.first().showNextEvent)
    }
}
