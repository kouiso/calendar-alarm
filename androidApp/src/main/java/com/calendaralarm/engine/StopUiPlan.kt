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

        /**
         * 鳴動の通知が「見える停止手段」として使えるか (#153)。アプリ全体の通知 ON に加えて、
         * 実際に鳴動に使うチャンネル (バイブ設定で選ぶ方) がヘッズアップの出る重要度 (HIGH 以上) であること。
         * 個別に止められたり下げられたりしていると、アプリ全体は ON のまま通知の「停止」が画面に出ないので、
         * オーバーレイや鳴動画面の自動表示を止める手段として残す判定に使う。使わない方のチャンネルは見ない
         */
        fun notificationsUsable(context: Context, vibrate: Boolean = true): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return true
            if (!nm.areNotificationsEnabled()) return false
            val id = if (vibrate) AlarmService.CHANNEL_ID else AlarmService.CHANNEL_ID_NOVIB
            val importance = runCatching { nm.getNotificationChannel(id)?.importance }.getOrNull()
                ?: return true // 未作成なら鳴動時に HIGH で作られる
            return importance >= NotificationManager.IMPORTANCE_HIGH
        }

        fun probe(context: Context, vibrate: Boolean = true): StopUiPermissions {
            probeOverride?.let { return it(context) }
            val nm = context.getSystemService(NotificationManager::class.java)
            return StopUiPermissions(
                notificationsOk = runCatching { notificationsUsable(context, vibrate) }.getOrDefault(true),
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
        /**
         * 鳴動画面 (またはオーバーレイ) を自動で前に出すか (#153)。強制 ON なら出す。
         * 強制 OFF でも、通知が出せない時は画面上の停止手段が無くなるので出す。
         * オーバーレイとアプリ前面時の自動表示 (MainActivity) で同じ判定を使う
         */
        fun autoShowStopScreen(forceFullScreen: Boolean, notificationsOk: Boolean): Boolean =
            forceFullScreen || !notificationsOk

        fun of(p: StopUiPermissions, forceFullScreen: Boolean = true): StopUiPlan = StopUiPlan(
            // 強制 OFF でも、通知が出せない時はオーバーレイが唯一の停止手段なので残す
            showOverlay = p.overlayOk && autoShowStopScreen(forceFullScreen, p.notificationsOk),
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
