package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    /** ボタンが押された。キーかモニターの切り替えを送り、結果(成功なら null)を `done` で返す */
    private val onAction: (action: Action, done: (String?) -> Unit) -> Unit,
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

    /** 消灯した・点灯したときに呼ぶ(Activity がバックライトを絞る・戻す) */
    var onSleepChanged: (Boolean) -> Unit = {}

    /** 触らずにこの時間が過ぎたら消灯する。0 なら消灯しない。設定するとその時点から数え直す */
    var sleepTimeoutMs = 0L
        set(value) {
            field = value
            idle = IdleTimer(value, SystemClock.elapsedRealtime())
            updateSleep()
        }

    private var idle = IdleTimer(0, SystemClock.elapsedRealtime())
    private var sleeping = false

    /** 消灯中に触った指が離れるまで、その操作をボタンに渡さない */
    private var swallowing = false

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
    private val path = Path()

    private val ticker = object : Runnable {
        override fun run() {
            updateSleep()
            if (!sleeping) invalidate()
            postDelayed(this, 1_000)
        }
    }

    private fun updateSleep() {
        val s = idle.isSleeping(SystemClock.elapsedRealtime())
        if (s == sleeping) return
        sleeping = s
        pressed = null
        invalidate()
        onSleepChanged(s)
    }

    /** 触ったのと同じに扱い、消灯していれば点ける(Activity に戻ったときなど) */
    fun wake() {
        idle.touch(SystemClock.elapsedRealtime())
        updateSleep()
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
        if (sleeping) {
            // 消灯中は真っ黒にする(バックライトは Activity が最小にする)
            c.drawColor(Color.BLACK)
            return
        }
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

        // 左のレール: キオスクのモニターの状態と 消灯 / 点灯 / 自動、いちばん下に設定。
        // 現在の画面は中央に大きく出すので、画面一覧は置かない(直接遷移ができるまで)
        drawMonitor(c, status?.display, fresh == Freshness.OFFLINE)
        layout.displayKeys.forEach { drawButton(c, it, now) }
        drawButton(c, layout.settings, now)

        // 中央: 現在の画面
        val ctr = layout.center
        val dim = fresh == Freshness.OFFLINE
        val titleColor = if (dim) MUTED else FG
        when {
            !configured -> {
                label(c, "未設定", ctr.left + dp(24f), ctr.top + dp(64f), 40f, FG, bold)
                label(c, "左下の「設定」でキオスクの URL とトークンを入れる", ctr.left + dp(24f), ctr.top + dp(100f), 18f, MUTED)
            }
            status == null -> {
                label(c, "接続中…", ctr.left + dp(24f), ctr.top + dp(64f), 40f, MUTED, bold)
            }
            else -> {
                val cur = status.currentScreen
                val idx = status.current?.let { "%02d ".format(it + 1) } ?: ""
                label(c, idx + (cur?.title ?: "-"), ctr.left + dp(24f), ctr.top + dp(64f), 40f, titleColor, bold)
                val detail = buildList {
                    status.page?.let { add(it) }
                    if (cur?.id == "photo") status.photo?.let { (p, t) -> add("写真 $p / $t") }
                    add(if (status.feedOnline) "feed online" else "feed retrying")
                }.joinToString("   ")
                label(c, detail, ctr.left + dp(24f), ctr.top + dp(100f), 20f, MUTED)
                status.rotation?.let { drawRotation(c, it, now, dim) }
            }
        }
        // エラーは 1 行だけ。送信の失敗を優先する
        val err = lastError?.let { "送信できない: $it" }
            ?: state.lastError?.takeIf { fresh != Freshness.FRESH || status == null }
        err?.let { label(c, it, ctr.left + dp(24f), ctr.top + dp(128f), 16f, NG) }

        layout.mainKeys.forEach { drawButton(c, it, now) }
        layout.sideKeys.forEach { drawButton(c, it, now) }
    }

    /** キオスクのモニターの状態。点灯中・消灯中と、手動オーバーライドの今の値 */
    private fun drawMonitor(c: Canvas, d: DisplayInfo?, dim: Boolean) {
        val row = layout.monitorStatus
        label(c, "モニター", row.left + dp(8f), row.top + dp(20f), 16f, MUTED)
        val (state, color) = when {
            !configured || d == null -> "-" to MUTED
            d.on -> "点灯中" to if (dim) MUTED else OK
            else -> "消灯中" to if (dim) MUTED else WARN
        }
        dot(c, row.left + dp(14f), row.top + dp(43f), color)
        label(c, state, row.left + dp(28f), row.top + dp(50f), 20f, if (dim) MUTED else FG, bold)
        val mode = when (d?.mode) {
            "auto" -> "自動"
            "force_on" -> "常に点灯"
            "force_off" -> "常に消灯"
            else -> null
        }
        mode?.let { label(c, it, row.left + dp(28f), row.top + dp(72f), 16f, MUTED) }
    }

    /**
     * キオスクの自動巡回(エラーの行と、前の画面・決定・次の画面のあいだ)。止まっていれば、戻るまでの残りを毎秒数え下げる。
     * 巡回が無効なキオスクでは出さない
     */
    private fun drawRotation(c: Canvas, r: RotationInfo, now: Long, dim: Boolean) {
        val ctr = layout.center
        val y = ctr.top + dp(176f)
        val (s, color) = if (r.paused) {
            // キオスクもパネルも切り上げるので、0 になってから戻るまで 1 秒ほどかかる
            val left = when (val secs = state.rotationResumeInS(now)) {
                null -> ""
                0L -> "  まもなく再開"
                else -> "  あと ${formatMinSec(secs)} で再開"
            }
            "巡回停止中$left" to WARN
        } else {
            "自動巡回中" to OK
        }
        dot(c, ctr.left + dp(30f), y - dp(7f), if (dim) MUTED else color)
        label(c, s, ctr.left + dp(44f), y, 20f, if (dim) MUTED else FG, bold)
    }

    /** 押せるか。モニターのボタンはキオスクが切り替えられると言っているときだけ */
    private fun usable(b: Button): Boolean = when (b.action) {
        Action.Settings -> true
        is Action.Key -> b.enabled && configured
        is Action.Display -> configured && state.status?.display?.control == true
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int) {
        fill.color = color
        c.drawCircle(x, y, dp(6f), fill)
    }

    private fun drawButton(c: Canvas, b: Button, now: Long) {
        val usable = usable(b)
        val failed = (failedUntil[b.id] ?: 0) > now
        val selected = (b.action as? Action.Display)?.mode?.let { it == state.status?.display?.mode } == true
        val bg = when {
            failed -> NG_DIM
            b.id == pressed && usable -> ACCENT
            !usable -> PANEL
            selected -> ACCENT_DIM
            b.action == Action.Settings -> PANEL
            else -> KEY
        }
        roundRect(c, b.box, bg)
        val fg = if (usable) FG else DISABLED
        when {
            b.icon == null -> label(c, b.label, b.box.centerX, b.box.centerY + dp(7f), 20f, fg, bold, Paint.Align.CENTER)
            // 縦長(前の画面・決定・次の画面): アイコンを中央に大きく、ラベルを下に
            b.box.height > dp(150f) -> {
                drawIcon(c, b.icon, b.box.centerX, b.box.centerY - dp(14f), dp(34f), fg)
                label(c, b.label, b.box.centerX, b.box.bottom - dp(22f), 22f, fg, bold, Paint.Align.CENTER)
            }
            // 横長(上・下・メニュー): アイコンを左に、ラベルを右に
            else -> {
                drawIcon(c, b.icon, b.box.left + dp(50f), b.box.centerY, dp(24f), fg)
                label(c, b.label, b.box.left + dp(96f), b.box.centerY + dp(8f), 22f, fg, bold)
            }
        }
    }

    /** アイコンを (cx, cy) を中心に、半径 r ほどの大きさで塗る */
    private fun drawIcon(c: Canvas, icon: Icon, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        when (icon) {
            Icon.PREV -> triangle(c, cx - r, cy, cx + r * 0.7f, cy - r, cx + r * 0.7f, cy + r)
            Icon.NEXT -> triangle(c, cx + r, cy, cx - r * 0.7f, cy - r, cx - r * 0.7f, cy + r)
            Icon.UP -> triangle(c, cx, cy - r, cx - r, cy + r * 0.7f, cx + r, cy + r * 0.7f)
            Icon.DOWN -> triangle(c, cx, cy + r, cx - r, cy - r * 0.7f, cx + r, cy - r * 0.7f)
            Icon.SELECT -> c.drawCircle(cx, cy, r * 0.75f, fill)
            Icon.MENU -> {
                val h = r * 0.26f
                for (dy in listOf(-r * 0.7f, 0f, r * 0.7f)) {
                    rect.set(cx - r, cy + dy - h / 2, cx + r, cy + dy + h / 2)
                    c.drawRoundRect(rect, h / 2, h / 2, fill)
                }
            }
        }
    }

    private fun triangle(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        c.drawPath(path, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // 消灯中の最初のタッチは点けるだけ。ボタンは押さない
            swallowing = sleeping
            wake()
        }
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
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
        if (b.action == Action.Settings) {
            playSoundEffect(SoundEffectConstants.CLICK)
            onSettings()
            return
        }
        if (!usable(b)) return
        // タップで即送る。204 で押下音と触覚。失敗したら赤く点滅して再送しない(設計書 §5.6)
        onAction(b.action) { err ->
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
