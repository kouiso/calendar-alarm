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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** スライド停止の閾値 (RingingActivity/iOS の3実装で同じ値に揃える) */
internal const val SLIDE_STOP_THRESHOLD = 0.8f

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
    fun show(
        context: Context,
        instanceId: String,
        title: String,
        snoozeMinutes: Int,
        snoozePresets: List<Int> = emptyList(),
        stillValid: () -> Boolean = { true },
    ) {
        if (!canShow(context)) return
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            // 投稿から実行までの間に停止が走った場合は表示しない。
            // hide() の投稿が先に処理されていてもここで止められる
            if (!stillValid()) return@post
            removeNow(app)
            val wm = app.getSystemService(WindowManager::class.java) ?: run {
                audit(app, "OVERLAY_FAIL", "$instanceId: WindowManager取得不可")
                return@post
            }
            val view = buildView(app, instanceId, title, snoozeMinutes, snoozePresets)
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

    /**
     * 任意スレッドから呼んでよい。実際の removeView はメインへ投げる。
     * overlayView == null でも必ず投稿する: show() の投稿がまだ実行されていない
     * 場合に hide を取りこぼすと、停止後も全画面UIが残る (投稿順に show → remove
     * が走るので null チェックで早退しない)。
     */
    fun hide(context: Context) {
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
            val repo = (context as com.calendaralarm.CalendarAlarmApp)
                .container.repository
            CoroutineScope(Dispatchers.IO).launch { repo.audit(action, detail) }
        }
    }

    private fun buildView(
        context: Context,
        instanceId: String,
        title: String,
        snoozeMinutes: Int,
        snoozePresets: List<Int>,
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
        // プリセット展開で縦が溢れても停止スライダーへ辿り着けるよう全体をスクロール可能にする
        val scrollView = ScrollView(context).apply { addView(root) }

        // 時刻・タイトルは ScrollView (fillViewport) 内に配置し、低い画面や
        // プリセット展開時に溢れてもスクロールで到達できるようにする。
        // 下部の操作群は root 直下で固定。
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        top.addView(TextView(context).apply {
            text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date())
            textSize = 96f
            setTextColor(fg)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f,
        ).apply { gravity = Gravity.CENTER })

        top.addView(TextView(context).apply {
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
        top.addView(spacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f,
        ))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(top)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
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

        // 別分数スヌーズは開閉式: 鳴動画面 (RingingActivity) と同じく
        // 「スヌーズ時間を選ぶ ▾」を押した時だけプリセットチップを出す。
        // サービスへの ACTION_SNOOZE は EXTRA_SNOOZE_MINUTES で分数を渡す。
        if (snoozePresets.isNotEmpty()) {
            // プリセットが多くても横スクロールで分数を読めるようにする (1行詰め込みで潰れない)
            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
                addView(chipRow)
            }
            fun sendSnooze(minutes: Int) {
                runCatching {
                    context.startService(
                        Intent(context, AlarmService::class.java).apply {
                            action = AlarmService.ACTION_SNOOZE
                            putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
                            putExtra(AlarmService.EXTRA_SNOOZE_MINUTES, minutes)
                        },
                    )
                }
            }
            // 鳴動画面と同じく最大6個: それ以上は1行に詰まり分数が読めない。
            for (m in snoozePresets.take(6)) {
                val chip = TextView(context).apply {
                    text = "+${m}分"
                    textSize = 14f
                    setTextColor(fg)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor("#14FFFFFF"))
                    }
                    setOnClickListener { sendSnooze(m) }
                }
                chipRow.addView(chip, LinearLayout.LayoutParams(
                    dp(64), dp(40),
                ).apply { marginStart = dp(4); marginEnd = dp(4) })
            }
            val toggle = TextView(context).apply {
                text = "スヌーズ時間を選ぶ ▾"
                textSize = 14f
                setTextColor(sub)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    val open = chipScroll.visibility != View.VISIBLE
                    chipScroll.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "スヌーズ時間を選ぶ ▴" else "スヌーズ時間を選ぶ ▾"
                }
            }
            root.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) })
            root.addView(chipScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // 停止はスライド式: 誤タップで止まらないよう左端の丸を右へ80%ドラッグ。
        // TalkBack はこのトラックへのダブルタップ (performClick) で停止できる。
        root.addView(buildSlideToStop(context, instanceId, accent, fg),
            LinearLayout.LayoutParams(
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

        return scrollView
    }

    /**
     * 停止スライダー (View版)。ドラッグ量がトラック幅の80%を超えたら停止。
     * 鳴動通知の「停止」と同じ放送経路で止めるので、サービス不在でも AlarmReceiver が
     * サービス停止 + DB終端化を行い必ず届く。
     * タップは OnTouchListener が消費してスナップバックするだけに留め、
     * アクセシビリティの ACTION_CLICK → performClick のみ OnClickListener で停止する。
     */
    private fun buildSlideToStop(
        context: Context,
        instanceId: String,
        accent: Int,
        fg: Int,
    ): View {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val thumbSize = dp(56)
        val pad = dp(8)

        fun dismiss() {
            context.sendBroadcast(
                Intent(context, AlarmReceiver::class.java).apply {
                    action = AlarmService.ACTION_DISMISS
                    putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
                },
            )
        }

        val slider = FrameLayout(context).apply {
            contentDescription = "スライドして停止"
            background = GradientDrawable().apply {
                cornerRadius = dp(36).toFloat()
                setColor(Color.parseColor("#14FFFFFF"))
                // アクセントの35%枠: RingingActivity のスライダーと同じ視認性
                setStroke(dp(1), Color.argb(89, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }

        val label = TextView(context).apply {
            text = "スライドして停止 ›"
            textSize = 20f
            setTextColor(fg)
            gravity = Gravity.CENTER
        }
        slider.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // 丸の中は停止の■ (デザイン正本: 矢印ではなく停止アイコン)
        val thumb = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        thumb.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26247A"))
                cornerRadius = dp(3).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.CENTER
        })
        slider.addView(thumb, FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = pad
        })

        var downX = 0f
        // 掴み始めが丸の範囲内の時だけドラッグを受理: トラック上の任意スワイプで
        // 止まってしまうと誤停止になるため起点を縛る (RingingActivity と同じ判定)
        var grabbed = false
        slider.setOnTouchListener { v, ev ->
            val travel = (v.width - thumbSize - pad * 2).coerceAtLeast(1).toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabbed = ev.x <= thumbSize + pad * 3
                    downX = ev.x
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!grabbed) return@setOnTouchListener true
                    val dx = (ev.x - downX).coerceIn(0f, travel)
                    thumb.translationX = dx
                    label.alpha = (1f - dx / travel).coerceIn(0f, 1f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!grabbed) return@setOnTouchListener true
                    grabbed = false
                    if ((ev.x - downX) >= travel * SLIDE_STOP_THRESHOLD) {
                        thumb.translationX = travel
                        dismiss()
                    } else {
                        thumb.animate().translationX(0f).setDuration(120).start()
                        label.animate().alpha(1f).setDuration(120).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (grabbed) {
                        thumb.animate().translationX(0f).setDuration(120).start()
                        label.animate().alpha(1f).setDuration(120).start()
                    }
                    grabbed = false
                    true
                }
                else -> true
            }
        }
        // TalkBack ダブルタップ (performClick) で停止。通常タップは OnTouch が
        // 消費するのでここには来ず、スナップバックだけになる。
        slider.setOnClickListener { dismiss() }
        return slider
    }
}
