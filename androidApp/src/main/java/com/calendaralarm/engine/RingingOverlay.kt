package com.calendaralarm.engine

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.calendaralarm.R
import com.calendaralarm.shared.logic.SlideToStopRule
import com.calendaralarm.ui.theme.AppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

    // サービス経由で実際に表示された View を、テストが中身まで検査できるようにする
    @VisibleForTesting
    internal fun shownViewForTest(): View? = overlayView

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
        // 既定値を持たせない: 省略してもコンパイルが通ると、呼び出し側の渡し忘れで
        // オーバーレイだけチップと見出し・大ボタンが静かに消える (鳴動画面とのズレ)
        snoozePresets: List<Int>,
        largeButtons: Boolean,
        // 同じ理由で既定値を持たせない: 渡し忘れると色と時刻が鳴動画面と静かにズレる (#336)
        triggerAtMillis: Long,
        eventStartMillis: Long?,
        themeId: String,
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
            val view = buildView(
                app, instanceId, title, snoozeMinutes, snoozePresets, largeButtons,
                triggerAtMillis = triggerAtMillis,
                eventStartMillis = eventStartMillis,
                themeId = themeId,
            )
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
                    // FLAG_DISMISS_KEYGUARD は付けない: PIN ロック中に解除要求が出ると
                    // 鳴動画面の上に PIN 入力が重なり停止ボタンが押せなくなる (PR #54 と同根)
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
            val container = (context as com.calendaralarm.CalendarAlarmApp).container
            // 画面側 (RingingActivity) と同じアプリ寿命のスコープで書く。
            // オーバーレイを閉じても書き込みが途中で消えないようにするため
            container.appScope.launch { container.repository.audit(action, detail) }
        }
    }

    // 停止スライダーの操作契約を単体テストで固定するため、WindowManager を通さずに組めるようにする。
    // 寸法と色は鳴動画面 (RingingActivity) と同じ RingingSlideSpec / AppPalette.forRinging から引く (#336)
    @VisibleForTesting
    internal fun buildView(
        context: Context,
        instanceId: String,
        title: String,
        snoozeMinutes: Int,
        snoozePresets: List<Int>,
        largeButtons: Boolean = false,
        triggerAtMillis: Long = System.currentTimeMillis(),
        eventStartMillis: Long? = null,
        themeId: String = "default",
        nowMillis: () -> Long = System::currentTimeMillis,
    ): View {
        val palette = AppPalette.forRinging(themeId)
        val accent = palette.primary.toArgb()
        val onAccent = palette.onPrimary.toArgb()
        val fg = RingingSlideSpec.TEXT_ARGB
        val buttonFg = RingingSlideSpec.BUTTON_TEXT_ARGB
        val sub = RingingSlideSpec.SUB_TEXT_ARGB
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        fun pill(text: String, bg: Int, fgColor: Int): TextView = TextView(context).apply {
            this.text = text
            // 大きいボタンモード (#154) は盤 p154 の .bb (24px・太さ600・角丸32)
            textSize = (if (largeButtons) RingingSlideSpec.LARGE_BUTTON_TEXT_SP else RingingSlideSpec.SNOOZE_TEXT_SP).toFloat()
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            setTypeface(semiBold(typeface))
            background = GradientDrawable().apply {
                cornerRadius = dp(
                    if (largeButtons) RingingSlideSpec.LARGE_BUTTON_CORNER_DP else RingingSlideSpec.TRACK_CORNER_DP,
                ).toFloat()
                setColor(bg)
            }
        }

        val padH = dp(RingingSlideSpec.SCREEN_PAD_H_DP)
        val padV = dp(RingingSlideSpec.SCREEN_PAD_V_DP)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 鳴動画面と同じ不透明の背景: 透けると別のアプリの画面が見えて同じに見えない
            setBackgroundColor(RingingSlideSpec.BACKGROUND_ARGB)
            setPadding(padH, padV, padH, padV)
            // 鳴動画面は WindowInsets.safeDrawing で端を避けている。同じく、システムバーと画面の切り欠きの幅を足す
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                v.setPadding(padH + bars.left, padV + bars.top, padH + bars.right, padV + bars.bottom)
                insets
            }
        }
        // 日付〜題は ScrollView (fillViewport) 内に配置し、低い画面や
        // プリセット展開時に溢れてもスクロールで到達できるようにする。
        // 下部の操作群は root 直下で固定。
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 日付の札
        top.addView(TextView(context).apply {
            text = SimpleDateFormat(context.getString(R.string.date_pattern_month_day_weekday), Locale.JAPAN)
                .format(Date(triggerAtMillis))
            tag = RING_DATE_TAG
            textSize = RingingSlideSpec.DATE_TEXT_SP.toFloat()
            setTextColor(sub)
            gravity = Gravity.CENTER
            setPadding(dp(RingingSlideSpec.DATE_PAD_H_DP), 0, dp(RingingSlideSpec.DATE_PAD_H_DP), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(RingingSlideSpec.DATE_CORNER_DP).toFloat()
                setColor(RingingSlideSpec.DATE_FILL_ARGB)
                setStroke(dp(1), RingingSlideSpec.DATE_STROKE_ARGB)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(RingingSlideSpec.DATE_HEIGHT_DP),
        ))

        // 二重の輪と時刻。大きさは入れ物 (RingDialArea) が枠の高さと幅から決める
        val timeView = TextView(context).apply {
            text = SimpleDateFormat(RingingSlideSpec.TIME_PATTERN, Locale.getDefault()).format(Date(triggerAtMillis))
            tag = RING_TIME_TAG
            setTextColor(fg)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            // 書体が読めなくても停止には関わらないので、端末の既定の書体で出す
            runCatching { ResourcesCompat.getFont(context, R.font.outfit_extralight) }.getOrNull()
                ?.let { setTypeface(it) }
        }
        val dialFrame = FrameLayout(context).apply {
            addView(RingDialView(context, accent, RingingSlideSpec.dialInnerRatio(largeButtons)).apply {
                tag = RING_DIAL_TAG
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(timeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        val dialGap = View(context)
        val area = RingDialArea(context, largeButtons, dialFrame, dialGap, timeView)
        // 大きさは RingDialArea.onMeasure が決める
        area.addView(dialFrame, LinearLayout.LayoutParams(0, 0))
        area.addView(dialGap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))

        area.addView(TextView(context).apply {
            text = startsInText(context, eventStartMillis, nowMillis())
            tag = RING_STARTS_IN_TAG
            textSize = RingingSlideSpec.STARTS_IN_TEXT_SP.toFloat()
            setTextColor(accent)
            setTypeface(semiBold(typeface))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(RingingSlideSpec.STARTS_IN_HEIGHT_DP),
        ))
        area.addView(View(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(RingingSlideSpec.STARTS_IN_TO_TITLE_DP),
        ))

        area.addView(TextView(context).apply {
            text = title.ifBlank { "アラーム" }
            tag = TITLE_TAG
            textSize = (if (largeButtons) RingingSlideSpec.LARGE_TITLE_SP else RingingSlideSpec.TITLE_SP).toFloat()
            setTextColor(fg)
            setTypeface(semiBold(typeface))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            // 行の高さは鳴動画面の lineHeight (sp) と同じ
            TextViewCompat.setLineHeight(
                this,
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (largeButtons) RingingSlideSpec.LARGE_TITLE_LINE_SP else RingingSlideSpec.TITLE_LINE_SP.toFloat(),
                    context.resources.displayMetrics,
                ).roundToInt(),
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        top.addView(area, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(top)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val snooze = pill("スヌーズ ${snoozeMinutes}分", RingingSlideSpec.BUTTON_FILL_ARGB, buttonFg)
        snooze.tag = SNOOZE_BUTTON_TAG
        snooze.setOnClickListener {
            // 停止ボタンと同じく押下の事実を先に残す。スヌーズが効かなかった時に
            // タップ未到達 (ログ無し) とサービス側の失敗 (TAP だけある) を切り分けるため
            audit(context.applicationContext, "SNOOZE_TAP", "オーバーレイ: $instanceId")
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
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (largeButtons) RingingSlideSpec.LARGE_BUTTON_HEIGHT_DP else RingingSlideSpec.SNOOZE_HEIGHT_DP),
        ))

        // 別分数スヌーズは開閉式: 鳴動画面 (RingingActivity) と同じく
        // 「スヌーズ時間を選ぶ ▾」を押した時だけプリセットチップを出す。
        // サービスへの ACTION_SNOOZE は EXTRA_SNOOZE_MINUTES で分数を渡す。
        // 大きいボタンモード (#154) では盤 p154 の 2 列目どおり出さない
        if (snoozePresets.isNotEmpty() && !largeButtons) {
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
                    setTextColor(buttonFg)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(RingingSlideSpec.BUTTON_FILL_ARGB)
                    }
                    setOnClickListener { sendSnooze(m) }
                }
                chipRow.addView(chip, LinearLayout.LayoutParams(
                    dp(64), dp(40),
                ).apply { marginStart = dp(4); marginEnd = dp(4) })
            }
            val toggle = TextView(context).apply {
                tag = SNOOZE_CHOOSE_TAG
                text = "スヌーズ時間を選ぶ ▾"
                textSize = RingingSlideSpec.SNOOZE_CHOOSE_TEXT_SP.toFloat()
                setTextColor(sub)
                gravity = Gravity.CENTER
                setOnClickListener {
                    val open = chipScroll.visibility != View.VISIBLE
                    chipScroll.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "スヌーズ時間を選ぶ ▴" else "スヌーズ時間を選ぶ ▾"
                }
            }
            // 高さは鳴動画面と同じ固定値 (文字の行の高さに任せると位置がズレる)
            root.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(RingingSlideSpec.SNOOZE_CHOOSE_HEIGHT_DP),
            ))
            root.addView(chipScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // 停止はスライド式: 誤タップで止まらないよう左端の丸を右へ80%ドラッグ。
        // TalkBack はこのトラックへのダブルタップ (performClick) で停止できる。
        val stop = if (largeButtons) {
            android.widget.Button(context).apply {
                text = context.getString(R.string.common_stop)
                textSize = RingingSlideSpec.LARGE_BUTTON_TEXT_SP.toFloat()
                tag = LARGE_STOP_TAG
                isAllCaps = false
                setTextColor(onAccent)
                setTypeface(semiBold(typeface))
                background = GradientDrawable().apply {
                    cornerRadius = dp(RingingSlideSpec.LARGE_BUTTON_CORNER_DP).toFloat()
                    setColor(accent)
                }
                setOnClickListener { dismiss(context, instanceId) }
            }
        } else {
            buildSlideToStop(context, instanceId, accent, onAccent, buttonFg)
        }
        root.addView(stop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(if (largeButtons) RingingSlideSpec.LARGE_BUTTON_HEIGHT_DP else RingingSlideSpec.TRACK_HEIGHT_DP),
            ).apply { topMargin = dp(RingingSlideSpec.STOP_GAP_DP) })

        // 最後の子のままにする (RingingOverlayLargeLayoutTest が最後の子の上の余白を見る)
        root.addView(TextView(context).apply {
            text = "Calendar Alarm"
            textSize = RingingSlideSpec.FOOT_TEXT_SP.toFloat()
            setTextColor(RingingSlideSpec.FOOT_TEXT_ARGB)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(RingingSlideSpec.FOOT_HEIGHT_DP),
        ).apply {
            topMargin = dp(if (largeButtons) RingingSlideSpec.LARGE_BUTTON_GAP_DP else RingingSlideSpec.FOOT_GAP_DP)
        })

        return root
    }

    private fun boldOf(base: android.graphics.Typeface?): android.graphics.Typeface =
        android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD)

    /** 盤の太さ 600。API 28 未満は太さ指定が無いので通常の太字で代える */
    private fun semiBold(base: android.graphics.Typeface?): android.graphics.Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.graphics.Typeface.create(base, 600, false)
        } else {
            boldOf(base)
        }

    private fun dismiss(context: Context, instanceId: String) {
        audit(context.applicationContext, "STOP_TAP", "オーバーレイ: $instanceId")
        context.sendBroadcast(
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
            },
        )
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
        onAccent: Int,
        fg: Int,
    ): View {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val thumbSize = dp(RingingSlideSpec.THUMB_DP)
        val pad = dp(RingingSlideSpec.PAD_DP)

        val slider = FrameLayout(context).apply {
            contentDescription = "スライドして停止"
            tag = SLIDE_TRACK_TAG
            background = GradientDrawable().apply {
                cornerRadius = dp(RingingSlideSpec.TRACK_CORNER_DP).toFloat()
                setColor(RingingSlideSpec.BUTTON_FILL_ARGB)
                // アクセントの35%枠: RingingActivity のスライダーと同じ視認性
                setStroke(
                    dp(1),
                    Color.argb(
                        (RingingSlideSpec.TRACK_STROKE_ALPHA * 255).roundToInt(),
                        Color.red(accent), Color.green(accent), Color.blue(accent),
                    ),
                )
            }
        }

        val label = TextView(context).apply {
            text = "スライドして停止 ›"
            textSize = RingingSlideSpec.SLIDE_LABEL_SP.toFloat()
            setTextColor(fg)
            gravity = Gravity.CENTER
        }
        slider.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // 丸の中は停止の■ (デザイン正本: 矢印ではなく停止アイコン)
        val thumb = FrameLayout(context).apply {
            tag = SLIDE_THUMB_TAG
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        thumb.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(onAccent)
                cornerRadius = dp(3).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(RingingSlideSpec.STOP_ICON_DP), dp(RingingSlideSpec.STOP_ICON_DP)).apply {
            gravity = Gravity.CENTER
        })
        slider.addView(thumb, FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = pad
        })

        var downX = 0f
        // 掴み始めが丸の範囲内の時だけドラッグを受理: トラック上の任意スワイプで
        // 止まってしまうと誤停止になるため起点を縛る。幅と閾値は鳴動画面・iOS と共通の SlideToStopRule
        var grabbed = false
        slider.setOnTouchListener { v, ev ->
            val travel = (v.width - thumbSize - pad * 2).coerceAtLeast(1).toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabbed = SlideToStopRule.isGrab(ev.x, thumbSize.toFloat(), pad.toFloat())
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
                    if (SlideToStopRule.shouldStop((ev.x - downX) / travel)) {
                        thumb.translationX = travel
                        dismiss(context, instanceId)
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
        slider.setOnClickListener { dismiss(context, instanceId) }
        return slider
    }
}

/**
 * 鳴動画面と同じ「○分後に開始」「まもなく開始」。四捨五入も同じ ((残り + 30秒) / 1分)。
 * 開始時刻が無い (通知由来・タイマー等) か、開始を過ぎていれば「まもなく開始」。
 */
internal fun startsInText(context: Context, eventStartMillis: Long?, nowMillis: Long): String {
    val remain = eventStartMillis?.minus(nowMillis)
    return if (remain != null && remain > 0) {
        val minutes = ((remain + 30_000) / 60_000).toInt()
        context.resources.getQuantityString(R.plurals.ring_starts_in_minutes, minutes, minutes)
    } else {
        context.getString(R.string.ring_starts_soon)
    }
}

/**
 * 二重の輪を描く View。大きさは親の枠いっぱい (外の輪)。内の輪は外の [innerRatio] 倍の直径。
 * 値は鳴動画面 (Compose の border) と同じ RingingSlideSpec から引く。
 */
private class RingDialView(
    context: Context,
    private val color: Int,
    private val innerRatio: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun withAlpha(alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // 線は円の内側に収める (Compose の border と同じ)
        val outerStroke = RingingSlideSpec.DIAL_OUTER_STROKE_DP * density
        paint.strokeWidth = outerStroke
        paint.color = withAlpha(RingingSlideSpec.DIAL_OUTER_ALPHA)
        canvas.drawCircle(cx, cy, (size - outerStroke) / 2f, paint)
        val innerStroke = RingingSlideSpec.DIAL_INNER_STROKE_DP * density
        paint.strokeWidth = innerStroke
        paint.color = withAlpha(RingingSlideSpec.DIAL_INNER_ALPHA)
        canvas.drawCircle(cx, cy, (size * innerRatio - innerStroke) / 2f, paint)
    }
}

/**
 * 輪・「○分後に開始」・題を入れる枠。自分の高さと幅 (dp) から輪の大きさを決めて、
 * 子の大きさと時刻の文字の大きさに反映してから測る。式は鳴動画面と同じ RingingSlideSpec.dialScale / dialOuterDp。
 * 中は縦に中央寄せで、入りきらない分は clipChildren で切る (上の段の ScrollView がスクロールする)。
 */
private class RingDialArea(
    context: Context,
    private val largeButtons: Boolean,
    private val dialFrame: View,
    private val dialGap: View,
    private val timeView: TextView,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        clipChildren = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val areaHeightDp = MeasureSpec.getSize(heightMeasureSpec) / density
        val areaWidthDp = MeasureSpec.getSize(widthMeasureSpec) / density
        val scale = RingingSlideSpec.dialScale(areaHeightDp, largeButtons)
        val outerPx = (RingingSlideSpec.dialOuterDp(areaHeightDp, areaWidthDp, largeButtons) * density).roundToInt()
        // LayoutParams の値を直に書き換える (測る最中に requestLayout を呼ばない)
        (dialFrame.layoutParams as LinearLayout.LayoutParams).apply { width = outerPx; height = outerPx }
        (dialGap.layoutParams as LinearLayout.LayoutParams).height =
            (RingingSlideSpec.DIAL_TO_STARTS_IN_DP * scale * density).roundToInt()
        val timeRatio = RingingSlideSpec.dialTimeRatio(largeButtons)
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, outerPx * timeRatio)
        // View の字の間は文字の大きさに対する割合。Compose の「外の輪 × -4/290」と同じ長さにする
        timeView.letterSpacing = RingingSlideSpec.DIAL_TIME_LETTER_SPACING_PER_DIAL / timeRatio
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
