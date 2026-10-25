package com.calendaralarm.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** GDPR/EEA の同意状態を確認し、必要なら同意フォームを出してから GMA SDK を初期化する。
     *  同意不要の地域ではフォームは出ずそのまま進む。1度だけ呼ぶ。 */
    fun gatherConsentAndInit(activity: Activity) {
        if (started) return
        started = true
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        consentInfo.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                // 同意情報の更新に成功。フォームが要る地域だけここで出る
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    finishConsent(activity.applicationContext)
                }
            },
            { _ /* error: FormError */ ->
                // 同意情報の取得失敗 (オフライン等) でも前回の状態を尊重して進める
                finishConsent(activity.applicationContext)
            },
        )
    }

    private fun finishConsent(appContext: Context) {
        val consentInfo = UserMessagingPlatform.getConsentInformation(appContext)
        if (!consentInfo.canRequestAds()) return
        _canRequestAds.value = true
        // SDK 初期化はバックグラウンドスレッドで (公式推奨)。鳴動処理は待たせない
        Thread {
            runCatching { MobileAds.initialize(appContext) }
        }.start()
    }
}
