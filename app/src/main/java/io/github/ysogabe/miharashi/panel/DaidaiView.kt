package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 画面 B(だいだい連携画面)の描画(設計書 §5.6)。View と Canvas だけで描く。
 * [render] で受けた [DaidaiUiState] を描くだけで、マイクも通信も扱わない。呼び出しは UI スレッドから。
 * 色は操作盤(A)の配色トークンと、照度による色補正(`contrastStrength`)に従う。
 * テキストは常にプレーンに描き(HTML として解釈しない)、制御文字などは描画前にも除く。
 */
@SuppressLint("ViewConstructor")
class DaidaiView(context: Context, private val avatars: AvatarAssets = AvatarAssets()) : View(context) {
    /** 照度による色補正。`PanelView.contrastStrength` と同じ値を受け取る */
    var contrastStrength = 0f
        set(value) {
            val next = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            if (field == next) return
            field = next
            invalidate()
        }

    /** 今描いている状態(テスト用に読み取りだけ公開) */
    var uiState = DaidaiUiState()
        private set

    /** 描画用に整えた文字列。[render] で一度だけ作る */
    private class Prepared(
        val title: String, val body: String, val transcript: String, val reply: String,
    )

    private var prepared = prepare(uiState)

    /** 受け取った状態を描く。UI スレッドから呼ぶ。同じ状態なら描き直さない */
    fun render(state: DaidaiUiState) {
        if (state == uiState) return
        uiState = state
        prepared = prepare(state)
        invalidate()
    }

    private fun prepare(s: DaidaiUiState) = Prepared(
        title = DaidaiText.clean(s.notice?.title, DaidaiInfo.MAX_TITLE, keepNewlines = false),
        body = DaidaiText.clean(s.notice?.body, DaidaiInfo.MAX_BODY, keepNewlines = true),
        transcript = DaidaiText.clean(s.transcript, DaidaiText.MAX_TRANSCRIPT, keepNewlines = false),
        reply = DaidaiText.clean(s.reply, DaidaiText.MAX_REPLY, keepNewlines = true),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    private val path = Path()

    private var layoutCache: DaidaiLayout? = null

    private fun layoutFor(hasCard: Boolean): DaidaiLayout {
        val w = width.toFloat()
        val h = height.toFloat()
        val old = layoutCache
        if (old != null && old.width == w && old.height == h && old.hasCard == hasCard) return old
        return DaidaiLayout(w, h, hasCard).also { layoutCache = it }
    }

    private fun contrastColor(base: Int, target: Int): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * contrastStrength).toInt()
        return Color.rgb(channel(Color.red(base), Color.red(target)),
            channel(Color.green(base), Color.green(target)), channel(Color.blue(base), Color.blue(target)))
    }

    private val BG get() = contrastColor(PanelView.BASE_BG, Color.BLACK)
    private val PANEL get() = contrastColor(PanelView.BASE_PANEL, Color.rgb(10, 14, 20))
    private val FG get() = contrastColor(PanelView.BASE_FG, Color.WHITE)
    private val MUTED get() = contrastColor(PanelView.BASE_MUTED, Color.rgb(200, 210, 220))

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        val s = uiState
        val p = prepared
        val guide = s.guide
        val lay = layoutFor(hasCard = guide == SetupGuide.NONE && s.notice != null)
        c.drawColor(BG)
        drawHeader(c, lay, s)
        avatars.draw(c, if (guide == SetupGuide.NONE) s.avatarPose else DaidaiPose.IDLE, lay.avatar, avatarColors())
        if (guide != SetupGuide.NONE) {
            drawGuide(c, lay, guide)
            return
        }
        lay.card?.let { drawCard(c, lay, it, s, p) }
        drawStatus(c, lay, s, p)
        drawReply(c, lay, s, p)
    }

    private fun avatarColors() = AvatarColors(
        face = PanelView.ACCENT, feature = contrastColor(PanelView.BASE_BG, Color.BLACK), mark = WARN)

    // ---- ヘッダー: 左に「だいだい」、右にマイク使用中(A のヘッダーと同じ矩形・帯の色)

    private fun drawHeader(c: Canvas, lay: DaidaiLayout, s: DaidaiUiState) {
        val h = lay.header
        fillBox(c, h, PANEL, 0f)
        val baseY = h.centerY + lay.px(8f)
        text(c, "だいだい", h.left + lay.px(20f), baseY, lay.px(22f), PanelView.ACCENT, bold)
        if (!s.micActive) return
        val label = "マイク使用中"
        val size = lay.px(20f)
        bold.textSize = size
        val textW = bold.measureText(label)
        val right = h.right - lay.px(20f)
        val dotR = lay.px(7f)
        val pillL = right - textW - lay.px(16f) - dotR * 2 - lay.px(12f)
        fillBox(c, Box(pillL, h.centerY - lay.px(18f), right + lay.px(10f), h.centerY + lay.px(18f)), MIC_PILL, lay.px(18f))
        fill.color = MIC_RED
        c.drawCircle(pillL + lay.px(12f) + dotR, h.centerY, dotR, fill)
        text(c, label, right, baseY, size, FG, bold, Paint.Align.RIGHT)
    }

    // ---- 通知カード

    private fun drawCard(c: Canvas, lay: DaidaiLayout, card: Box, s: DaidaiUiState, p: Prepared) {
        fillBox(c, card, PANEL, lay.px(12f))
        val pad = lay.padding
        val inner = Box(card.left + pad, card.top + pad, card.right - pad, card.bottom - pad)
        var titleRight = inner.right
        if (s.moreNotices > 0) {
            val more = "他 ${s.moreNotices} 件"
            val size = lay.px(18f)
            regular.textSize = size
            titleRight = inner.right - regular.measureText(more) - lay.px(12f)
            text(c, more, inner.right, inner.top + lay.px(22f), size, MUTED, regular, Paint.Align.RIGHT)
        }
        val titleSize = lay.px(24f)
        if (p.title.isNotEmpty()) {
            bold.textSize = titleSize
            val t = TextFitter.ellipsize(p.title, titleRight - inner.left) { bold.measureText(it) }
            text(c, t, inner.left, inner.top + lay.px(24f), titleSize, FG, bold)
        }
        if (p.body.isNotEmpty()) {
            val top = inner.top + lay.px(36f)
            val fit = TextFitter.fit(p.body, inner.width, DaidaiLayout.CARD_BODY_MAX_LINES,
                DaidaiLayout.CARD_BODY_SIZES.map { lay.px(it) }) { size, str -> regular.textSize = size; regular.measureText(str) }
            drawLines(c, fit, inner.left, top, MUTED, regular)
        }
    }

    // ---- 状態チップ + 認識結果

    private fun drawStatus(c: Canvas, lay: DaidaiLayout, s: DaidaiUiState, p: Prepared) {
        val color = stateColor(s.voice)
        val chip = lay.chip
        fillBox(c, chip, PANEL, chip.height / 2)
        stroke.color = color
        stroke.strokeWidth = lay.px(2f)
        rect.set(chip.left, chip.top, chip.right, chip.bottom)
        c.drawRoundRect(rect, chip.height / 2, chip.height / 2, stroke)
        val size = lay.px(22f)
        text(c, DaidaiText.stateLabel(s.voice), chip.centerX, chip.centerY + size * 0.35f, size, color, bold, Paint.Align.CENTER)
        if (p.transcript.isNotEmpty()) {
            val tb = lay.transcript
            val tsize = lay.px(22f)
            regular.textSize = tsize
            val line = TextFitter.ellipsize("「${p.transcript}」", tb.width) { regular.measureText(it) }
            text(c, line, tb.left, tb.centerY + tsize * 0.35f, tsize, FG, regular)
        }
    }

    private fun stateColor(state: VoiceState) = when (state) {
        VoiceState.IDLE -> contrastColor(PanelView.BASE_MUTED, Color.rgb(200, 210, 220))
        VoiceState.LISTENING, VoiceState.HEARING -> PanelView.ACCENT
        VoiceState.WAITING -> WARN
        VoiceState.REPLYING -> OK
        VoiceState.ERROR -> NG
    }

    // ---- 返答・エラーの吹き出し・「話しかけてください」

    private fun drawReply(c: Canvas, lay: DaidaiLayout, s: DaidaiUiState, p: Prepared) {
        val box = lay.reply
        if (s.voice == VoiceState.ERROR) {
            drawErrorBubble(c, lay, box, DaidaiText.errorMessage(s.error))
            return
        }
        if (p.reply.isNotEmpty()) {
            val fit = fitReply(lay, p.reply, box.width)
            drawLines(c, fit, box.left, box.top + lay.px(4f), FG, regular)
            return
        }
        if (s.voice == VoiceState.IDLE || s.voice == VoiceState.LISTENING) {
            val size = lay.px(32f)
            text(c, DaidaiText.HINT, box.left, box.top + lay.px(8f) + size, size, MUTED, bold)
        }
    }

    private fun fitReply(lay: DaidaiLayout, str: String, width: Float) =
        TextFitter.fit(str, width, DaidaiLayout.REPLY_MAX_LINES, DaidaiLayout.REPLY_SIZES.map { lay.px(it) }) { size, t ->
            regular.textSize = size
            regular.measureText(t)
        }

    /** アバターの方へしっぽの出た吹き出しに、固定の文言を出す */
    private fun drawErrorBubble(c: Canvas, lay: DaidaiLayout, box: Box, message: String) {
        val pad = lay.padding
        val fit = fitReply(lay, message, box.width - pad * 2)
        val lineH = fit.size * DaidaiLayout.LINE_HEIGHT
        val bottom = minOf(box.bottom, box.top + pad * 2 + lineH * fit.lines.size)
        val bubble = Box(box.left, box.top, box.right, bottom)
        fillBox(c, bubble, NG_DIM, lay.px(12f))
        stroke.color = NG
        stroke.strokeWidth = lay.px(2f)
        rect.set(bubble.left, bubble.top, bubble.right, bubble.bottom)
        c.drawRoundRect(rect, lay.px(12f), lay.px(12f), stroke)
        // しっぽ
        val tipY = bubble.top + lay.px(26f)
        path.reset()
        path.moveTo(bubble.left - lay.px(14f), tipY)
        path.lineTo(bubble.left + 1f, tipY - lay.px(10f))
        path.lineTo(bubble.left + 1f, tipY + lay.px(10f))
        path.close()
        fill.color = NG_DIM
        c.drawPath(path, fill)
        drawLines(c, fit, bubble.left + pad, bubble.top + pad, FG, regular)
    }

    // ---- 未設定・権限なしの案内

    private fun drawGuide(c: Canvas, lay: DaidaiLayout, guide: SetupGuide) {
        val box = lay.guide
        val titleSize = lay.px(32f)
        text(c, DaidaiText.guideTitle(guide), box.left, box.top + titleSize, titleSize, FG, bold)
        val fit = TextFitter.fit(DaidaiText.guideBody(guide), box.width, DaidaiLayout.GUIDE_MAX_LINES,
            DaidaiLayout.GUIDE_SIZES.map { lay.px(it) }) { size, t -> regular.textSize = size; regular.measureText(t) }
        drawLines(c, fit, box.left, box.top + titleSize + lay.px(20f), MUTED, regular)
    }

    // ---- 部品

    private fun drawLines(c: Canvas, fit: TextFitter.Fit, x: Float, top: Float, color: Int, p: Paint) {
        val lineH = fit.size * DaidaiLayout.LINE_HEIGHT
        for ((i, line) in fit.lines.withIndex()) {
            text(c, line, x, top + lineH * i + fit.size, fit.size, color, p)
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, p: Paint, align: Paint.Align = Paint.Align.LEFT) {
        p.textSize = size
        p.color = color
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    private fun fillBox(c: Canvas, b: Box, color: Int, radius: Float) {
        fill.color = color
        rect.set(b.left, b.top, b.right, b.bottom)
        if (radius > 0f) c.drawRoundRect(rect, radius, radius, fill) else c.drawRect(rect, fill)
    }

    /** 触れた指は自分が受ける(ページの横ドラッグは `PagerLayout` が途中で奪う) */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = true

    private companion object {
        val OK = Color.rgb(102, 220, 120)
        val WARN = Color.rgb(255, 196, 64)
        val NG = Color.rgb(255, 92, 92)
        val NG_DIM = Color.rgb(90, 28, 28)

        /** マイク使用中の目立つ赤 */
        val MIC_RED = Color.rgb(255, 64, 64)
        val MIC_PILL = Color.rgb(110, 20, 20)
    }
}
