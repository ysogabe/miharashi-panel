package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作盤の描画とタップの判定(設計書 §5.4)。View と Canvas だけで描く。
 * 状態は持たず、`state` が変わるたびに描き直す。
 */
@SuppressLint("ViewConstructor")
class PanelView(
    context: Context,
    /** ボタンが押された。`key` を送り、結果(成功なら null)を `done` で返す */
    private val onKey: (key: String, done: (String?) -> Unit) -> Unit,
    private val onSettings: () -> Unit,
) : View(context) {

    var state = PanelState()
        set(value) {
            field = value
            invalidate()
        }

    /** 設定(ホストとトークン)が入っているか */
    var configured = false
        set(value) {
            field = value
            invalidate()
        }

    var wifi = false
        set(value) {
            field = value
            invalidate()
        }

    private var layout = PanelLayout(PanelLayout.BASE_W, PanelLayout.BASE_H)
    private var pressed: String? = null

    /** ボタンごとの、失敗表示を消す時刻(elapsedRealtime) */
    private val failedUntil = mutableMapOf<String, Long>()
    private var lastError: String? = null

    private val clock = SimpleDateFormat("HH:mm", Locale.JAPAN)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = PanelLayout(w.toFloat(), h.toFloat())
    }

    private fun dp(v: Float) = v * layout.scale

    private fun roundRect(c: Canvas, b: Box, color: Int, radius: Float = 14f) {
        fill.color = color
        rect.set(b.left, b.top, b.right, b.bottom)
        c.drawRoundRect(rect, dp(radius), dp(radius), fill)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, p: Paint = text, align: Paint.Align = Paint.Align.LEFT) {
        p.textSize = dp(size)
        p.color = color
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val fresh = state.freshness(now)
        val status = state.status
        c.drawColor(BG)

        // ヘッダー: 名前、ホスト、API と WIFI の点灯、時刻
        val h = layout.header
        roundRect(c, h, PANEL, 0f)
        val baseY = h.centerY + dp(8f)
        label(c, "MIHARASHI", h.left + dp(20f), baseY, 22f, ACCENT, bold)
        label(c, status?.host ?: "-", h.left + dp(190f), baseY, 18f, MUTED)
        val apiColor = when (fresh) {
            Freshness.FRESH -> OK
            Freshness.STALE -> WARN
            Freshness.OFFLINE -> NG
        }
        val apiLabel = when (fresh) {
            Freshness.FRESH -> "API"
            Freshness.STALE -> "STALE"
            Freshness.OFFLINE -> "OFFLINE"
        }
        dot(c, h.left + dp(470f), h.centerY, apiColor)
        label(c, apiLabel, h.left + dp(484f), baseY, 18f, FG, bold)
        dot(c, h.left + dp(610f), h.centerY, if (wifi) OK else NG)
        label(c, "WIFI", h.left + dp(624f), baseY, 18f, FG, bold)
        label(c, clock.format(Date()), h.right - dp(20f), baseY, 26f, FG, bold, Paint.Align.RIGHT)

        // 左のレール: キオスクの画面一覧。現在の画面を強調する(タップでの直接遷移は P3)
        label(c, "画面", layout.rail.left + dp(16f), layout.rail.top + dp(28f), 16f, MUTED)
        status?.screens?.forEachIndexed { i, s ->
            val row = layout.railRow(i)
            val current = i == status.current
            if (current) roundRect(c, row, ACCENT_DIM, 10f)
            val color = if (current) FG else MUTED
            label(c, "%02d".format(i + 1), row.left + dp(12f), row.centerY + dp(7f), 18f, color, bold)
            label(c, s.title, row.left + dp(52f), row.centerY + dp(7f), 18f, color, if (current) bold else text)
        }
        drawButton(c, layout.settings, now)

        // 中央: 現在の画面
        val ctr = layout.center
        val dim = fresh == Freshness.OFFLINE
        val titleColor = if (dim) MUTED else FG
        when {
            !configured -> {
                label(c, "未設定", ctr.left + dp(24f), ctr.top + dp(70f), 40f, FG, bold)
                label(c, "左下の「設定」でキオスクの URL とトークンを入れる", ctr.left + dp(24f), ctr.top + dp(116f), 18f, MUTED)
            }
            status == null -> {
                label(c, "接続中…", ctr.left + dp(24f), ctr.top + dp(70f), 40f, MUTED, bold)
                state.lastError?.let { label(c, it, ctr.left + dp(24f), ctr.top + dp(116f), 18f, NG) }
            }
            else -> {
                val cur = status.currentScreen
                val idx = status.current?.let { "%02d ".format(it + 1) } ?: ""
                label(c, idx + (cur?.title ?: "-"), ctr.left + dp(24f), ctr.top + dp(70f), 40f, titleColor, bold)
                val detail = buildList {
                    status.page?.let { add(it) }
                    if (cur?.id == "photo") status.photo?.let { (p, t) -> add("写真 $p / $t") }
                    add(if (status.feedOnline) "feed online" else "feed retrying")
                }.joinToString("   ")
                label(c, detail, ctr.left + dp(24f), ctr.top + dp(116f), 20f, MUTED)
                if (fresh != Freshness.FRESH) {
                    state.lastError?.let { label(c, it, ctr.left + dp(24f), ctr.top + dp(150f), 16f, NG) }
                }
            }
        }
        lastError?.let { label(c, "送信できない: $it", ctr.left + dp(24f), ctr.top + dp(196f), 16f, NG) }

        layout.mainKeys.forEach { drawButton(c, it, now) }
        layout.sideKeys.forEach { drawButton(c, it, now) }
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int) {
        fill.color = color
        c.drawCircle(x, y, dp(6f), fill)
    }

    private fun drawButton(c: Canvas, b: Button, now: Long) {
        val usable = b.enabled && (configured || b.key == null)
        val failed = (failedUntil[b.id] ?: 0) > now
        val bg = when {
            failed -> NG_DIM
            b.id == pressed && usable -> ACCENT
            !usable -> PANEL
            b.key == null -> PANEL
            else -> KEY
        }
        roundRect(c, b.box, bg)
        val fg = if (usable) FG else DISABLED
        if (b.digit.isEmpty()) {
            label(c, b.label, b.box.centerX, b.box.centerY + dp(7f), 20f, fg, bold, Paint.Align.CENTER)
        } else if (b.box.height > dp(150f)) {
            label(c, b.digit, b.box.centerX, b.box.centerY + dp(10f), 72f, fg, bold, Paint.Align.CENTER)
            label(c, b.label, b.box.centerX, b.box.bottom - dp(20f), 20f, fg, text, Paint.Align.CENTER)
        } else {
            label(c, b.digit, b.box.left + dp(24f), b.box.centerY + dp(16f), 44f, fg, bold)
            label(c, b.label, b.box.left + dp(80f), b.box.centerY + dp(8f), 20f, fg, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = layout.hit(e.x, e.y)?.id
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val b = layout.hit(e.x, e.y)
                val id = pressed
                pressed = null
                invalidate()
                if (b != null && b.id == id) tap(b)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }

    private fun tap(b: Button) {
        if (b.key == null) {
            playSoundEffect(SoundEffectConstants.CLICK)
            onSettings()
            return
        }
        if (!b.enabled || !configured) return
        // タップで即送る。204 で押下音と触覚。失敗したら赤く点滅して再送しない(設計書 §5.6)
        onKey(b.key) { err ->
            if (err == null) {
                lastError = null
                playSoundEffect(SoundEffectConstants.CLICK)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            } else {
                lastError = err
                failedUntil[b.id] = SystemClock.elapsedRealtime() + FAIL_FLASH_MS
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            invalidate()
        }
    }

    companion object {
        const val FAIL_FLASH_MS = 800L
        private val BG = Color.rgb(8, 11, 16)
        private val PANEL = Color.rgb(20, 26, 34)
        private val KEY = Color.rgb(32, 42, 56)
        private val FG = Color.rgb(230, 236, 242)
        private val MUTED = Color.rgb(132, 146, 162)
        private val DISABLED = Color.rgb(70, 80, 92)
        private val ACCENT = Color.rgb(79, 195, 247)
        private val ACCENT_DIM = Color.rgb(24, 64, 88)
        private val OK = Color.rgb(102, 220, 120)
        private val WARN = Color.rgb(255, 196, 64)
        private val NG = Color.rgb(255, 92, 92)
        private val NG_DIM = Color.rgb(120, 32, 32)
    }
}
