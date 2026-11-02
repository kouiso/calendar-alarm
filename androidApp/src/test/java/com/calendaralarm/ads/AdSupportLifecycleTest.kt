package com.calendaralarm.ads

import android.app.Activity
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdSupportLifecycleTest {
    private val fakeSdk = FakeAdConsentSdk()

    @Before
    fun setUp() {
        AdSupport.resetForTests()
        AdSupport.consentSdk = fakeSdk
    }

    @After
    fun tearDown() {
        AdSupport.resetForTests()
    }

    @Test
    fun replacementActivityStartsConsentAndStaleErrorIsIgnored() {
        val previousController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val previousActivity = previousController.get()
        AdSupport.gatherConsentAndInit(previousActivity)
        val obsolete = fakeSdk.updateCallbacks.single()

        previousController.destroy()
        assertTrue(previousActivity.isDestroyed)
        val replacementController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val replacementActivity = replacementController.get()
        AdSupport.gatherConsentAndInit(replacementActivity)

        assertEquals(listOf(previousActivity, replacementActivity), fakeSdk.requestedActivities)

        obsolete.onError()
        assertFalse(AdSupport.canRequestAds.value)
        assertTrue(fakeSdk.formActivities.isEmpty())

        fakeSdk.updateCallbacks.last().onSuccess()
        assertEquals(listOf(replacementActivity), fakeSdk.formActivities)
        fakeSdk.formCompletions.single().invoke()
        assertTrue(AdSupport.canRequestAds.value)

        replacementController.destroy()
    }

    @Test
    fun staleConsentInfoSuccessDoesNotShowFormForReplacement() {
        val previousController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val previousActivity = previousController.get()
        AdSupport.gatherConsentAndInit(previousActivity)
        val obsolete = fakeSdk.updateCallbacks.single()

        previousController.destroy()
        assertTrue(previousActivity.isDestroyed)
        val replacementController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val replacementActivity = replacementController.get()
        AdSupport.gatherConsentAndInit(replacementActivity)

        obsolete.onSuccess()
        assertTrue(fakeSdk.formActivities.isEmpty())
        assertFalse(AdSupport.canRequestAds.value)

        fakeSdk.updateCallbacks.last().onSuccess()
        assertEquals(listOf(replacementActivity), fakeSdk.formActivities)
        fakeSdk.formCompletions.single().invoke()
        assertTrue(AdSupport.canRequestAds.value)

        replacementController.destroy()
    }

    @Test
    fun staleFormCompletionDoesNotFinishReplacementAttempt() {
        val previousController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val previousActivity = previousController.get()
        AdSupport.gatherConsentAndInit(previousActivity)
        fakeSdk.updateCallbacks.single().onSuccess()
        val obsoleteFormCompletion = fakeSdk.formCompletions.single()

        previousController.destroy()
        assertTrue(previousActivity.isDestroyed)
        val replacementController = Robolectric.buildActivity(TestActivity::class.java).setup()
        val replacementActivity = replacementController.get()
        AdSupport.gatherConsentAndInit(replacementActivity)

        obsoleteFormCompletion()
        assertFalse(AdSupport.canRequestAds.value)

        fakeSdk.updateCallbacks.last().onSuccess()
        assertEquals(listOf(previousActivity, replacementActivity), fakeSdk.formActivities)
        fakeSdk.formCompletions.last().invoke()
        assertTrue(AdSupport.canRequestAds.value)

        replacementController.destroy()
    }

    private class TestActivity : Activity()

    private class FakeAdConsentSdk : AdConsentSdk {
        data class UpdateCallbacks(val onSuccess: () -> Unit, val onError: () -> Unit)

        val requestedActivities = mutableListOf<Activity>()
        val updateCallbacks = mutableListOf<UpdateCallbacks>()
        val formActivities = mutableListOf<Activity>()
        val formCompletions = mutableListOf<() -> Unit>()

        override fun requestConsentInfoUpdate(activity: Activity, onSuccess: () -> Unit, onError: () -> Unit) {
            requestedActivities += activity
            updateCallbacks += UpdateCallbacks(onSuccess, onError)
        }

        override fun loadAndShowConsentFormIfRequired(activity: Activity, onComplete: () -> Unit) {
            formActivities += activity
            formCompletions += onComplete
        }

        override fun canRequestAds(context: Context) = true

        override fun initialize(context: Context) = Unit
    }
}
