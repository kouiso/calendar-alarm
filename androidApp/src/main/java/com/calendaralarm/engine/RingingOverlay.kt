package com.calendaralarm.engine

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 鳴動中の「停止UIが1つも画面に出ない」事故への最終防衛オーバーレイ。
 *
 * 鳴動画面 (RingingActivity) はフルスクリーン Intent 経由でしか出せず、
 * FSI が効かない状況 (API34+ の権限拒否・サイドロード版の既定OFF・
 * 端末ロック解除済みで heads-up が畳まれた後) だと通知シェードを
 * 手繰る以外に停止手段が無くなる。SYSTEM_ALERT_WINDOW は
 * バックグラウンド起動制限 (BAL) の対象外なので、許可があれば
 * どの画面状態でも確実に停止ボタンを表示できる。
 *
 * 停止は AlarmReceiver 経由の放送 (鳴動通知と同じ経路) に流すため、
 * サービスが死んでいても必ず届く。スヌーズは鳴動中サービスへ送る。
 */
object RingingOverlay {

    // メインスレッドで更新・任意スレッドで読む。stale read で hide が
    // スキップされると全画面UIが残り得るため可視性を保証する
    @Volatile
    private var overlayView: View? = null

    fun isShowing() = overlayView != null

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    /**
     * 鳴動画面の代替として停止/スヌーズを全画面に表示する。既に出ていれば差し替える。
     * WindowManager 操作は呼び出し側のスレッドに関わらずメインで行う
     * (beginRinging 等は IO 上で動いており、そこで addView すると Looper 不在で失敗する)。
     * 成否は鳴動ログへ記録して次の障害の断線箇所を切り分けられるようにする。
     */
    fun show(context: Context, instanceId: String, title: String, snoozeMinutes: Int) {
        if (!canShow(context)) return
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            removeNow(app)
            val wm = app.getSystemService(WindowManager::class.java) ?: run {
                audit(app, "OVERLAY_FAIL", "$instanceId: WindowManager取得不可")
                return@post
            }
            val view = buildView(app, instanceId, title, snoozeMinutes)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // タップを受けるため NOT_FOCUSABLE は付けない。
                // ロック上にも出せる端末では出る (FSI が拾えない環境への保険)。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            try {
                wm.addView(view, params)
                overlayView = view
                audit(app, "OVERLAY_SHOWN", "$instanceId: 停止UIをオーバーレイで表示")
            } catch (e: Exception) {
                // オーバーレイ自体が拒否される環境では通知側だけが頼り。
                // ここで落とすと鳴動停止経路ごと死ぬので見逃し化する。
                audit(app, "OVERLAY_FAIL", "$instanceId: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 任意スレッドから呼んでよい。実際の removeView はメインへ投げる。 */
    fun hide(context: Context) {
        if (overlayView == null) return
        Handler(Looper.getMainLooper()).post { removeNow(context.applicationContext) }
    }

    /** メインスレッド上でのみ呼ぶこと。参照を消すのは削除を試みた後。 */
    private fun removeNow(context: Context) {
        val view = overlayView ?: return
        runCatching {
            context.getSystemService(WindowManager::class.java)?.removeView(view)
        }
        overlayView = null
    }

    private fun audit(context: Context, action: String, detail: String) {
        runCatching {
            (context as com.calendaralarm.CalendarAlarmApp)
                .container.repository.audit(action, detail)
        }
    }

    private fun buildView(
        context: Context,
        instanceId: String,
        title: String,
        snoozeMinutes: Int,
    ): View {
        val accent = Color.parseColor("#BDBDFF")
        val fg = Color.parseColor("#F4F5F8")
        val sub = Color.parseColor("#B7BECB")
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        fun pill(text: String, bg: Int, fgColor: Int): TextView = TextView(context).apply {
            this.text = text
            textSize = 22f
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(36).toFloat()
                setColor(bg)
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#F20A0C11"))
            val pad = dp(24)
            setPadding(pad, dp(48), pad, dp(40))
        }

        root.addView(TextView(context).apply {
            text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date())
            textSize = 96f
            setTextColor(fg)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f,
        ).apply { gravity = Gravity.CENTER })

        root.addView(TextView(context).apply {
            text = title.ifBlank { "アラーム" }
            textSize = 30f
            setTextColor(fg)
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val spacer = View(context)
        root.addView(spacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f,
        ))

        val snooze = pill("スヌーズ ${snoozeMinutes}分", Color.parseColor("#14FFFFFF"), fg)
        snooze.setOnClickListener {
            // 鳴動中はサービスが必ず生きているので startService で届く
            runCatching {
                context.startService(
                    Intent(context, AlarmService::class.java).apply {
                        action = AlarmService.ACTION_SNOOZE
                        putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
                    },
                )
            }
        }
        root.addView(snooze, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72),
        ))

        val dismiss = pill("停止", accent, Color.parseColor("#26247A"))
        dismiss.setOnClickListener {
            // 鳴動通知の「停止」と同じ放送経路: サービス不在でも AlarmReceiver が
            // サービス停止 + DB終端化を行うので必ず届く
            context.sendBroadcast(
                Intent(context, AlarmReceiver::class.java).apply {
                    action = AlarmService.ACTION_DISMISS
                    putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
                },
            )
        }
        root.addView(dismiss, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72),
        ).apply { topMargin = dp(12) })

        root.addView(TextView(context).apply {
            text = "Calendar Alarm"
            textSize = 12f
            setTextColor(sub)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        return root
    }
}
