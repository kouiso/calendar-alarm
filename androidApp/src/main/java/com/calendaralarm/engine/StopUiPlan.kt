package com.calendaralarm.engine

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** 鳴動時点で停止UIの各経路が使えるかどうか。 */
data class StopUiPermissions(
    val notificationsOk: Boolean,
    val fsiOk: Boolean,
    val overlayOk: Boolean,
) {
    companion object {
        // テストから権限状態を差し替えるための差込口 (Robolectric は FSI 権限を再現できない)
        @Volatile internal var probeOverride: ((Context) -> StopUiPermissions)? = null

        fun probe(context: Context): StopUiPermissions {
            probeOverride?.let { return it(context) }
            val nm = context.getSystemService(NotificationManager::class.java)
            return StopUiPermissions(
                notificationsOk = nm?.areNotificationsEnabled() ?: true,
                // API 33 以下は FSI が権限制ではないため常に可
                fsiOk = Build.VERSION.SDK_INT < 34 ||
                    (runCatching { nm?.canUseFullScreenIntent() }.getOrNull() ?: true),
                overlayOk = RingingOverlay.canShow(context),
            )
        }
    }
}

/**
 * 鳴動時に「どの停止UIを出すか」と「どの劣化状態を鳴動ログへ残すか」を一か所で決める。
 *
 * オーバーレイは FSI の可否に関係なく、許可があれば必ず出す。端末ロック解除中に
 * 他アプリ (動画など) が前面だと FSI は heads-up にしかならず、数秒で畳まれた後は
 * オーバーレイだけが画面上の停止手段になる。二重表示は鳴動画面側
 * (RingingActivity がオーバーレイを畳む / DB 終端で自動終了) で解消する。
 *
 * 「フルスクリーンを強制」OFF (#153) の時は、全画面のオーバーレイも出さない。
 * 通知 (ヘッズアップ) の「停止」と、通知を開いて出る鳴動画面だけで止める。
 * ただし通知が OFF だと画面上の停止手段が無くなるので、その時だけはオーバーレイを出す。
 */
data class StopUiPlan(
    val showOverlay: Boolean,
    val warnings: List<String>,
) {
    companion object {
        fun of(p: StopUiPermissions, forceFullScreen: Boolean = true): StopUiPlan = StopUiPlan(
            // 強制 OFF でも、通知が出せない時はオーバーレイが唯一の停止手段なので残す
            showOverlay = p.overlayOk && (forceFullScreen || !p.notificationsOk),
            warnings = buildList {
                if (!forceFullScreen) {
                    add(
                        if (p.notificationsOk) {
                            "フルスクリーン強制OFF: 鳴動画面とオーバーレイは自動表示せず、通知の「停止」か通知を開いて止める"
                        } else {
                            "フルスクリーン強制OFF: 通知OFFのため、停止手段としてオーバーレイだけは出す"
                        },
                    )
                }
                if (!p.notificationsOk) {
                    add("通知OFF: 通知の「停止」と鳴動画面の自動表示が使えない")
                }
                if (!p.fsiOk) {
                    add("FSI拒否: ロック中・画面OFF時に鳴動画面が自動表示されない")
                }
                if (!p.overlayOk) {
                    add("オーバーレイ未許可 (FSI=${if (p.fsiOk) "可" else "拒否"}): ロック解除中は通知シェードからのみ停止可能")
                }
            },
        )
    }
}
