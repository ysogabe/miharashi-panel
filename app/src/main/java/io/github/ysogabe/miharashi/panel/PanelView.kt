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
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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

    /** 角の丸い枠を、線の太さ `width` で `b` の内側に描く */
    private fun strokeRoundRect(c: Canvas, b: Box, color: Int, width: Float, radius: Float = 14f) {
        stroke.color = color
        stroke.strokeWidth = dp(width)
        val half = dp(width) / 2
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        c.drawRoundRect(rect, dp(radius) - half, dp(radius) - half, stroke)
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
        when {
            !configured -> {
                label(c, "未設定", ctr.left + dp(24f), ctr.top + dp(64f), 40f, FG, bold)
                label(c, "左下の「設定」でキオスクの URL とトークンを入れる", ctr.left + dp(24f), ctr.top + dp(100f), 18f, MUTED)
            }
            status == null -> {
                label(c, "接続中…", ctr.left + dp(24f), ctr.top + dp(64f), 40f, MUTED, bold)
            }
            status.menu != null -> drawMenu(c, status, status.menu, dim, now)
            else -> drawScreenCard(c, status, now, dim)
        }
        // エラーは 1 行だけ。送信の失敗を優先する
        val err = lastError?.let { "送信できない: $it" }
            ?: state.lastError?.takeIf { fresh != Freshness.FRESH || status == null }
        // メニュー中は一覧の下(キーのボタンとのあいだ)に、カードがあればその中の、画面の詳細と巡回の帯のあいだに出す
        val (errX, errY) = when {
            status?.menu != null -> ctr.left + dp(24f) to layout.menuArea.bottom + dp(18f)
            status != null && configured -> layout.screenCard.left + dp(24f) to ctr.top + dp(128f)
            else -> ctr.left + dp(24f) to ctr.top + dp(128f)
        }
        err?.let { label(c, it, errX, errY, 16f, NG) }

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
     * 現在の画面のカード。上に画面の名前と詳細、下の帯にキオスクの自動巡回(巡回が無効なキオスクでは帯を出さない)。
     * キオスクが巡回を止められると言っていれば(miharashi #57)、カード全体を押せるボタンにして枠を付け、
     * 帯の右に押すと何が起きるか(固定 / 再開)を出す。固定中は青い面と枠、右上に「固定」の札
     */
    private fun drawScreenCard(c: Canvas, status: KioskStatus, now: Long, dim: Boolean) {
        val card = layout.screenCard
        val r = status.rotation
        val fixed = r?.state == RotationState.OFF
        val tappable = rotationTappable()
        val bg = when {
            tappable && (failedUntil[ROTATION_ID] ?: 0) > now -> NG_DIM
            tappable && pressed == ROTATION_ID -> ACCENT
            fixed -> ACCENT_DIM
            else -> PANEL
        }
        roundRect(c, card, bg)
        val edge = if (fixed) (if (dim) MUTED else ACCENT) else KEY
        if (fixed) strokeRoundRect(c, card, edge, 3f) else if (tappable) strokeRoundRect(c, card, edge, 2f)

        val badgeW = dp(88f)
        if (fixed) {
            val badge = Box(card.right - dp(16f) - badgeW, card.top + dp(16f), card.right - dp(16f), card.top + dp(52f))
            roundRect(c, badge, if (dim) MUTED else ACCENT, 18f)
            label(c, "固定", badge.centerX, badge.centerY + dp(7f), 20f, BG, bold, Paint.Align.CENTER)
        }
        val cur = status.currentScreen
        val idx = status.current?.let { "%02d ".format(it + 1) } ?: ""
        val title = idx + (cur?.title ?: "-")
        // 長い画面の名前は、札に重ならないように縮める
        val titleW = card.width - dp(48f) - if (fixed) badgeW + dp(8f) else 0f
        label(c, title, card.left + dp(24f), card.top + dp(56f), fitSize(title, titleW, 40f, 24f), if (dim) MUTED else FG, bold)
        val detail = buildList {
            status.page?.let { add(it) }
            if (cur?.id == "photo") status.photo?.let { (p, t) -> add("写真 $p / $t") }
            add(if (status.feedOnline) "feed online" else "feed retrying")
        }.joinToString("   ")
        label(c, detail, card.left + dp(24f), card.top + dp(92f), 20f, MUTED)
        r?.let { drawRotation(c, it, now, dim, tappable, edge) }
    }

    /**
     * カードの下の帯に、キオスクの自動巡回。止まっていれば、戻るまでの残りを毎秒数え下げる。
     * 押せるなら、右に押すと何が起きるかを出す
     */
    private fun drawRotation(c: Canvas, r: RotationInfo, now: Long, dim: Boolean, tappable: Boolean, edge: Int) {
        val band = layout.rotationBand
        fill.color = edge
        c.drawRect(band.left + dp(16f), band.top, band.right - dp(16f), band.top + dp(1f), fill)
        val y = band.centerY + dp(7f)
        val (s, color) = when (r.state) {
            RotationState.PAUSED -> {
                // キオスクもパネルも切り上げるので、0 になってから戻るまで 1 秒ほどかかる
                val left = when (val secs = state.rotationResumeInS(now)) {
                    null -> ""
                    0L -> "  まもなく再開"
                    else -> "  あと ${formatMinSec(secs)} で再開"
                }
                "巡回停止中$left" to WARN
            }
            // OFFLINE で薄くしたとき(MUTED)と見分けられる色にする
            RotationState.OFF -> "この画面で固定中" to ACCENT
            RotationState.ON -> "自動巡回中" to OK
        }
        dot(c, band.left + dp(30f), y - dp(7f), if (dim) MUTED else color)
        label(c, s, band.left + dp(44f), y, 20f, if (dim) MUTED else FG, bold)
        if (tappable) {
            val (hint, hintColor) = when (r.state) {
                RotationState.ON -> "タップで この画面に固定" to MUTED
                // 残りの秒数で横に長くなるので短く
                RotationState.PAUSED -> "タップで 固定" to MUTED
                RotationState.OFF -> "タップで 巡回を再開" to ACCENT
            }
            label(c, hint, band.right - dp(20f), y, 18f, if (dim) MUTED else hintColor, text, Paint.Align.RIGHT)
        }
    }

    /** 現在の画面のカードが出ていて、巡回の固定・再開に押せるか(キオスクが `/api/v1/rotation` を受け付ける。メニュー中はカードを出さない) */
    private fun rotationTappable(): Boolean {
        val s = state.status ?: return false
        return configured && s.menu == null && s.rotation?.control == true
    }

    /** (x, y) が押せる現在の画面のカードの上か */
    private fun hitRotation(x: Float, y: Float): Boolean = rotationTappable() && layout.screenCard.contains(x, y)

    /**
     * キオスクのメニュー(miharashi #49)。画面の一覧を出し、選んでいる行を強調する。表示中の画面には「表示中」。
     * 行をタップすると、その画面へ移る(`/api/v1/screen`。キオスクはメニューも閉じる)
     */
    private fun drawMenu(c: Canvas, status: KioskStatus, selected: Int, dim: Boolean, now: Long) {
        for ((i, row) in layout.menuRows(status.screens.size, selected)) {
            val id = menuRowId(i)
            val failed = (failedUntil[id] ?: 0) > now
            val bg = when {
                failed -> NG_DIM
                id == pressed -> ACCENT
                i == selected -> ACCENT_DIM
                else -> PANEL
            }
            roundRect(c, row, bg, 10f)
            if (i == selected) {
                fill.color = if (dim) MUTED else ACCENT
                rect.set(row.left, row.top, row.left + dp(6f), row.bottom)
                c.drawRoundRect(rect, dp(3f), dp(3f), fill)
            }
            val y = row.centerY + dp(7f)
            val color = if (dim) MUTED else FG
            label(c, "%02d".format(i + 1), row.left + dp(18f), y, 18f, MUTED, bold)
            label(c, status.screens[i].title, row.left + dp(56f), y, 20f, color, bold)
            if (i == status.current) label(c, "表示中", row.right - dp(14f), y, 16f, MUTED, text, Paint.Align.RIGHT)
        }
    }

    private fun menuRowId(i: Int) = "menu:$i"

    /** メニューが開いていれば、(x, y) にある行の画面の添字 */
    private fun hitMenu(x: Float, y: Float): Int? {
        val s = state.status ?: return null
        val selected = s.menu ?: return null
        return layout.menuRows(s.screens.size, selected).firstOrNull { it.second.contains(x, y) }?.first
    }

    /**
     * ボタンに出すラベル。キーはキオスクが返す今の意味(miharashi #51)。押しても何もしないキーは null。
     * 古いキオスク(keys を返さない)とキー以外のボタンは、決まったラベル
     */
    private fun labelOf(b: Button): String? {
        val name = (b.action as? Action.Key)?.name ?: return b.label
        val keys = state.status?.keys ?: return b.label
        return keys[name]
    }

    /** 押せるか。キーはキオスクが意味を返しているときだけ。モニターのボタンはキオスクが切り替えられると言っているときだけ */
    private fun usable(b: Button): Boolean = when (b.action) {
        Action.Settings -> true
        is Action.Key -> b.enabled && configured && labelOf(b) != null
        is Action.Display -> configured && state.status?.display?.control == true
        is Action.Screen -> configured
        is Action.Rotation -> rotationTappable()
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
        // 押しても何もしないキーは、汎用のラベルを灰色で出す
        val shown = labelOf(b) ?: b.label
        when {
            b.icon == null -> label(c, shown, b.box.centerX, b.box.centerY + dp(7f), 20f, fg, bold, Paint.Align.CENTER)
            // 縦長(前の画面・決定・次の画面): アイコンを中央に大きく、ラベルを下に
            b.box.height > dp(150f) -> {
                drawIcon(c, b.icon, b.box.centerX, b.box.centerY - dp(14f), dp(34f), fg)
                val size = fitSize(shown, b.box.width - dp(16f), 22f)
                label(c, shown, b.box.centerX, b.box.bottom - dp(22f), size, fg, bold, Paint.Align.CENTER)
            }
            // 横長(上・下・メニュー): アイコンを左に、ラベルを右に
            else -> {
                drawIcon(c, b.icon, b.box.left + dp(50f), b.box.centerY, dp(24f), fg)
                val size = fitSize(shown, b.box.right - b.box.left - dp(108f), 22f)
                label(c, shown, b.box.left + dp(96f), b.box.centerY + dp(8f), size, fg, bold)
            }
        }
    }
    /** `s` が `maxWidth` に収まる太字の大きさ(`size` から `min` まで縮める)。キオスクのラベルは全角 6 文字まで */
    private fun fitSize(s: String, maxWidth: Float, size: Float, min: Float = 14f): Float {
        var sp = size
        while (sp > min) {
            bold.textSize = dp(sp)
            if (bold.measureText(s) <= maxWidth) break
            sp -= 1f
        }
        return sp
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
                    ?: hitMenu(e.x, e.y)?.let(::menuRowId)
                    ?: ROTATION_ID.takeIf { hitRotation(e.x, e.y) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val b = layout.hit(e.x, e.y)
                val row = hitMenu(e.x, e.y)
                val id = pressed
                pressed = null
                invalidate()
                when {
                    b != null && b.id == id -> tap(b)
                    row != null && menuRowId(row) == id -> tapMenu(row)
                    id == ROTATION_ID && hitRotation(e.x, e.y) -> tapRotation()
                }
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
        send(b.action, b.id)
    }

    /** メニューの行: その画面へ移る(キオスクはメニューも閉じる) */
    private fun tapMenu(i: Int) {
        val screen = state.status?.screens?.getOrNull(i) ?: return
        if (!configured) return
        send(Action.Screen(screen.id), menuRowId(i))
    }

    /** 現在の画面のカード: 固定中なら巡回を再開し、回っているか一時停止中なら今の画面で固定する(miharashi #57) */
    private fun tapRotation() {
        val r = state.status?.rotation ?: return
        send(Action.Rotation(if (r.state == RotationState.OFF) "on" else "off"), ROTATION_ID)
    }

    /** タップで即送る。204 で押下音と触覚。失敗したら `failId` の場所を赤く点滅させ、再送しない(設計書 §5.6) */
    private fun send(action: Action, failId: String) {
        onAction(action) { err ->
            if (err == null) {
                lastError = null
                playSoundEffect(SoundEffectConstants.CLICK)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            } else {
                lastError = err
                failedUntil[failId] = SystemClock.elapsedRealtime() + FAIL_FLASH_MS
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            invalidate()
        }
    }

    companion object {
        const val FAIL_FLASH_MS = 800L
        private const val ROTATION_ID = "rotation"
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
