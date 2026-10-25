package com.calendaralarm.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.calendaralarm.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * 予定一覧の末尾に出すバナー1枠用の AdView (#140)。
 * 画面 (AgendaScreen) のスコープで1個だけ生成し、一覧の末尾アイテムが付け替えて使う。
 * 同意が取れるまで読み込まない。読込み失敗・在庫なし・オフラインは
 * 空の枠が残るだけで、アラーム操作や予定の表示には影響しない。
 */
@Composable
fun rememberBannerAdView(): AdView {
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
        }
    }
    val canRequest by AdSupport.canRequestAds.collectAsState()
    LaunchedEffect(canRequest) {
        if (canRequest) {
            runCatching { adView.loadAd(AdRequest.Builder().build()) }
        }
    }
    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }
    return adView
}

/**
 * 一覧末尾の広告枠。広告が出せる時だけ描く (同意待ち・失敗時は何も出さない)。
 * 末尾への追記なので既存の行やボタンの位置は動かない (CLS対策)。
 */
@Composable
fun BannerAdSlot(adView: AdView, modifier: Modifier = Modifier) {
    val canRequest by AdSupport.canRequestAds.collectAsState()
    if (!canRequest) return
    Column(
        modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "広告",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            factory = { adView },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        )
    }
}
