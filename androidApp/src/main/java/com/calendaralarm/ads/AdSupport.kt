package com.calendaralarm.ads

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

internal interface AdConsentSdk {
    fun requestConsentInfoUpdate(activity: Activity, onSuccess: () -> Unit, onError: () -> Unit)
    fun loadAndShowConsentFormIfRequired(activity: Activity, onComplete: () -> Unit)
    fun canRequestAds(context: Context): Boolean
    fun initialize(context: Context)
}

private object GoogleAdConsentSdk : AdConsentSdk {
    override fun requestConsentInfoUpdate(activity: Activity, onSuccess: () -> Unit, onError: () -> Unit) {
        UserMessagingPlatform.getConsentInformation(activity).requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            onSuccess,
            { onError() },
        )
    }

    override fun loadAndShowConsentFormIfRequired(activity: Activity, onComplete: () -> Unit) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { onComplete() }
    }

    override fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    override fun initialize(context: Context) {
        MobileAds.initialize(context)
    }
}

/**
 * 広告 (AdMob) の同意と初期化 (#140)。
 *
 * アラーム経路とは完全に分離し、全て非同期・フォアグラウンド Activity から進める。
 * 同意取得・SDK 初期化・広告読込みのどれかが失敗・遅延・オフラインでも
 * 「広告が出ない」だけに倒れ、鳴動・停止・スヌーズ・予定確認へ影響しない。
 * 予定のタイトルや内容は広告 SDK へ送らない (ターゲティング情報は一切渡さない)。
 */
object AdSupport {
    /** 広告を読み込んでよい状態か。同意が要る地域では同意後にだけ true になる */
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds

    @Volatile
    private var started = false
    private var consentActivity: WeakReference<Activity>? = null

    @VisibleForTesting
    internal var consentSdk: AdConsentSdk = GoogleAdConsentSdk

    /** GDPR/EEA の同意状態を確認し、必要なら同意フォームを出してから GMA SDK を初期化する。
     *  同意不要の地域ではフォームは出ずそのまま進む。1度だけ呼ぶ。
     *  鳴動・停止・スヌーズ操作を同意フォームで塞がないよう、鳴動中は表示を待つ。 */
    fun gatherConsentAndInit(activity: Activity, isAlerting: () -> Boolean = { false }) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (started) {
            val previous = consentActivity?.get()
            if (consentActivity == null || (previous != null && !previous.isFinishing && !previous.isDestroyed)) return
        }
        started = true
        consentActivity = WeakReference(activity)
        val sdk = consentSdk
        sdk.requestConsentInfoUpdate(
            activity,
            {
                // 同意情報の更新に成功。フォームが要る地域だけここで出る
                showConsentFormWhenIdle(activity, isAlerting, sdk)
            },
            {
                // 同意情報の取得失敗 (オフライン等) でも前回の状態を尊重して進める
                if (consentActivity?.get() === activity) finishConsent(activity.applicationContext, sdk)
            },
        )
    }

    private fun showConsentFormWhenIdle(activity: Activity, isAlerting: () -> Boolean, sdk: AdConsentSdk) {
        if (consentActivity?.get() !== activity || activity.isFinishing || activity.isDestroyed) return
        if (isAlerting()) {
            activity.window.decorView.postDelayed(
                { showConsentFormWhenIdle(activity, isAlerting, sdk) }, 500,
            )
            return
        }
        sdk.loadAndShowConsentFormIfRequired(activity) {
            if (consentActivity?.get() === activity) finishConsent(activity.applicationContext, sdk)
        }
    }

    private fun finishConsent(appContext: Context, sdk: AdConsentSdk) {
        consentActivity = null
        if (!sdk.canRequestAds(appContext)) {
            started = false
            return
        }
        _canRequestAds.value = true
        // SDK 初期化はバックグラウンドスレッドで (公式推奨)。鳴動処理は待たせない
        Thread {
            runCatching { sdk.initialize(appContext) }
        }.start()
    }

    @VisibleForTesting
    internal fun resetForTests() {
        started = false
        consentActivity = null
        _canRequestAds.value = false
        consentSdk = GoogleAdConsentSdk
    }
}
