package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * 画面 B(だいだい連携画面)の描画(設計書 §5.6)。文字や図形は Canvas で描き、アバターの動画だけ子の `TextureView` に任せる。
 * [render] で受けた [DaidaiUiState] を描くだけで、マイクも通信も扱わない。呼び出しは UI スレッドから。
 * 色は操作盤(A)の配色トークンと、照度による色補正(`contrastStrength`)に従う。
 *
 * アバターは、静止画(assets の PNG。なければ仮のドット絵)を `onDraw` で常に描き、その上にポーズの動画
 * (子の `TextureView`、[AvatarVideo] が再生を決める)を重ねる。動画は最初のフレームが出るまで隠し、
 * 開けないときは静止画のまま。静止画も動画も同じ角丸と、同じ照度の補正([AvatarContrast])を受ける。
 * テキストは常にプレーンに描き(HTML として解釈しない)、制御文字などは描画前にも除く。
 */
@SuppressLint("ViewConstructor")
class DaidaiView(
    context: Context,
    private val avatars: AvatarAssets = AvatarAssets.fromAssets(context.assets),
    videoPlayers: VideoPlayerFactory = VideoPlayerFactory { MediaPlayerVideoPlayer(context.assets) },
) : FrameLayout(context) {
    /** 照度による色補正。`PanelView.contrastStrength` と同じ値を受け取る */
    var contrastStrength = 0f
        set(value) {
            val next = AvatarContrast.clamp(value)
            if (field == next) return
            field = next
            applyVideoContrast()
            invalidate()
        }

    /**
     * アバターの動画を動かしてよいか(前面で、待機時計でない、と呼び出し側が判断したもの)。
     * 実際にデコードするのは、これが true で、かつこの View が実際に見えている(`visibility == VISIBLE`)ときだけ。
     * PagerLayout は B が画面に出ている間だけ B を VISIBLE にするので、B を引き始めただけで見えていない間
     * (A から右へ引く、端を引っ張る)も、デコードしない。静止画は常に出る。
     */
    var videoActive = false
        set(value) {
            if (field == value) return
            field = value
            syncVideo()
        }

    /** 動画を今動かしている(開いてよい)か(テスト用) */
    internal val videoRunning get() = videoActive && visibility == VISIBLE

    private fun syncVideo() = video.setActive(videoRunning)

    /**
     * PagerLayout が B の visibility を直接切り替えるので、ここで受ける(`onVisibilityChanged` は窓に付く前は呼ばれない)。
     * 親の表示切替は見ない。自分の `visibility` だけで判断する。
     */
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        // View の生成途中(`video` の初期化前)に呼ばれても何もしない
        if (videoReady) syncVideo()
    }

    // 動画レイヤー。隠すのは INVISIBLE ではなく alpha = 0: INVISIBLE の TextureView は描かれず、SurfaceTexture が
    // 作られない(作られても壊される)ことがあり、動画を開けなくなる。alpha 0 なら surface は生きたまま、見えないだけ。
    internal val videoLayer = TextureView(context).apply {
        alpha = 0f
        isClickable = false
        isFocusable = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, AvatarFrame.cornerRadius(minOf(view.width, view.height).toFloat()))
            }
        }
        clipToOutline = true
    }

    /** 動画レイヤーに渡している補正のペイント(テストで補正の有無を見る) */
    internal var videoLayerPaint = Paint()
        private set

    private val video = AvatarVideo(videoPlayers, { videoLayer.alpha = if (it) 1f else 0f })
    private var videoReady = false

    init {
        videoReady = true
        setWillNotDraw(false)
        addView(videoLayer, LayoutParams(0, 0))
        videoLayer.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = video.onSurfaceAvailable(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                video.onSurfaceDestroyed()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        applyVideoContrast()
    }

    private fun applyVideoContrast() {
        // `setLayerPaint` は同じ Paint のインスタンスだと何もしないので、変えるたびに新しい Paint を渡す(すぐ反映させる)
        videoLayerPaint = Paint().apply { colorFilter = AvatarContrast.filter(contrastStrength) }
        videoLayer.setLayerPaint(videoLayerPaint)
    }

    /** 動画レイヤーが今見えているか(テスト用) */
    internal val videoVisible get() = videoLayer.alpha > 0f

    override fun onDetachedFromWindow() {
        video.release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    /** 動画レイヤーは、静止画のアバター(`DaidaiLayout.avatar`)と同じ位置・大きさ */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        val a = DaidaiLayout(w.toFloat(), h.toFloat(), false).avatar
        val l = Math.round(a.left)
        val t = Math.round(a.top)
        val size = maxOf(Math.round(minOf(a.width, a.height)), 0)
        videoLayer.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        videoLayer.layout(l, t, l + size, t + size)
        videoLayer.invalidateOutline()
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
        video.setPose(displayPose(state))
        invalidate()
    }

    /** 今描くアバターのポーズ。案内の表示中は idle */
    private fun displayPose(s: DaidaiUiState) = if (s.guide == SetupGuide.NONE) s.avatarPose else DaidaiPose.IDLE

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
        avatars.draw(c, displayPose(s), lay.avatar, avatarColors())
        if (guide != SetupGuide.NONE) {
            drawGuide(c, lay, guide)
            return
        }
        lay.card?.let { drawCard(c, lay, it, s, p) }
        drawStatus(c, lay, s, p)
        drawReply(c, lay, s, p)
        drawBrainNotice(c, lay, s)
    }

    private fun avatarColors() = AvatarColors(
        face = PanelView.ACCENT, feature = contrastColor(PanelView.BASE_BG, Color.BLACK), mark = WARN, contrast = contrastStrength)

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
        // 返答音声の失敗など、返答テキストがあるのにエラーが付いているとき: テキストを残し、下に固定文言を小さく併記する
        val note = if (s.error != null) DaidaiText.errorMessage(s.error) else null
        if (p.reply.isNotEmpty()) {
            val fit = fitReply(lay, p.reply, box.width)
            val top = box.top + lay.px(4f)
            drawLines(c, fit, box.left, top, FG, regular)
            if (note != null) {
                val textBottom = top + fit.size * DaidaiLayout.LINE_HEIGHT * fit.lines.size
                drawReplyNote(c, lay, box, textBottom + lay.px(DaidaiLayout.REPLY_NOTE_GAP), note)
            }
            return
        }
        if (note != null) { // 返答が空でも、失敗は利用者に見せる
            drawReplyNote(c, lay, box, box.top + lay.px(4f), note)
            return
        }
        if (s.voice == VoiceState.IDLE || s.voice == VoiceState.LISTENING) {
            val size = lay.px(32f)
            text(c, DaidaiText.HINT, box.left, box.top + lay.px(8f) + size, size, MUTED, bold)
        }
    }

    /**
     * マイクが開いている間と合図の再生中の、どこへ送るかの表記(R15)。1 行で、収まらなければ省略。外へ出る(出るかもしれない)ときは警告色、
     * ホストの中だけのときは控えめな色。文言は固定で、ホストの文字列は使わない。
     */
    private fun drawBrainNotice(c: Canvas, lay: DaidaiLayout, s: DaidaiUiState) {
        val notice = s.brainNotice ?: return
        val box = lay.brainNotice
        val size = lay.px(DaidaiLayout.BRAIN_NOTICE_SIZE)
        regular.textSize = size
        val line = TextFitter.ellipsize(notice, box.width) { regular.measureText(it) }
        text(c, line, box.left, box.centerY + size * 0.35f, size, if (s.brain?.staysOnHost == true) MUTED else WARN, regular)
    }

    /** 返答の下に出す小さな失敗理由(1 行。収まらなければ省略)。吹き出しではなく、状態の色(警告色)の文字 */
    private fun drawReplyNote(c: Canvas, lay: DaidaiLayout, box: Box, top: Float, message: String) {
        val size = lay.px(DaidaiLayout.REPLY_NOTE_SIZE)
        regular.textSize = size
        val line = TextFitter.ellipsize(message, box.width) { regular.measureText(it) }
        text(c, line, box.left, top + size, size, WARN, regular)
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

    /**
     * ERROR 表示のタップ(再試行)。**利用者のタップだけ**が呼ぶ。ERROR 以外の状態のタップは無視する
     * (B のタップは、ERROR のときだけ意味を持つ)。
     */
    var onErrorTap: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false

    /** 触れた指は自分が受ける(ページの横ドラッグは `PagerLayout` が途中で奪い、そのとき `ACTION_CANCEL` が来る) */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downTime = e.eventTime
                tracking = true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) tracking = false
            }
            MotionEvent.ACTION_UP -> {
                val tap = tracking && e.eventTime - downTime <= ViewConfiguration.getTapTimeout() * 2L
                tracking = false
                if (tap && uiState.voice == VoiceState.ERROR) onErrorTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracking = false
        }
        return true
    }

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
