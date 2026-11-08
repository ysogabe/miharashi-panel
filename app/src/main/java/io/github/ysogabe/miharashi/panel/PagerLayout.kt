package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 2 枚のページ(A: 操作盤、B: だいだい)を横に並べ、ドラッグで送る(設計書 §3.1 P1, §5.7)。依存は増やさない。
 *
 * - 奪う条件: 水平移動が touchSlop を超え、かつ |dx| > 2|dy|。奪うと子には `ACTION_CANCEL` が送られる
 *   (`PanelView` は押下を取り消すので、ボタンの上から始まったスワイプでボタンは押されない。短いタップは従来どおり)
 * - 時計から復帰中の指(`gestureSwallowed`)では奪わない。MOVE のたびに読む
 * - 追従と戻りは `translationX`。[ANIMATION_MS] ms。動かしている間だけハードウェアレイヤー
 * - 端のページでさらに外へは動かさない(ほんの少し引っ張られるだけ)
 * - 移動が終わったらコントローラーに一度だけ知らせる(落ち着いた通知は `PageController` が出す)
 * - アニメーション中に指が触れても、進行中のアニメーションは完了させる(その指の操作は最後まで無視する)
 */
@SuppressLint("ViewConstructor")
class PagerLayout(
    context: Context,
    val pageA: View,
    val pageB: View,
    private val controller: PageController,
    /** 時計から復帰中の指の操作の最中か(`PanelView.gestureSwallowed`) */
    private val gestureSwallowed: () -> Boolean = { false },
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val decision: SwipeDecision = SwipeDecision(),
) : FrameLayout(context) {

    /** ページ点を描くか。待機時計を出している間は消す(焼き付きを避ける) */
    var dotsVisible = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 現在の位置。0 が A、1 が B。引っ張りで少しだけ範囲の外に出る */
    var position = 0f
        private set

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val velocity = SwipeVelocity()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 指の操作の状態
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var pointerId = -1
    private var dragging = false
    private var dragFrom = Page.A

    /** このジェスチャは無視する(アニメーション中に始まった、またはアニメーションなしで戻された) */
    private var ignoring = false

    // アニメーション
    private var animating = false
    private var animFrom = 0f
    private var animTo = 0f
    private var animStartMs = 0L
    private val frame = Runnable { stepAnimation() }

    private val listener = object : PageListener {
        override fun onMoveRequested(target: Page, reason: PageReason) = animateTo(if (target == Page.B) 1f else 0f)
        override fun onSnap(page: Page) = snapTo(if (page == Page.B) 1f else 0f)
    }

    init {
        setWillNotDraw(false)
        addView(pageA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        controller.addListener(listener)
        applyPosition()
    }

    // ---- タッチ ----

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // どのページが受けても、B の無操作タイマーは数え直す
        controller.touch(monotonicMs())
        val down = e.actionMasked == MotionEvent.ACTION_DOWN
        if (down) velocity.clear()
        val i = if (down) 0 else e.findPointerIndex(pointerId)
        if (i >= 0) velocity.add(e.eventTime, e.getX(i))
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startGesture(e)
                // アニメーション中は、指の操作を自分で受けて子に渡さない
                return ignoring
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoring) return true
                if (dragging) return true
                return maybeStartDrag(e)
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 子が受けなかった指は、ここで受ける(MOVE を受け取るため)
                startGesture(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoring) return true
                if (dragging) {
                    val i = e.findPointerIndex(pointerId)
                    if (i >= 0) dragTo(e.getX(i))
                } else {
                    maybeStartDrag(e)
                }
            }
            MotionEvent.ACTION_UP -> endGesture(e, cancelled = false)
            MotionEvent.ACTION_CANCEL -> endGesture(e, cancelled = true)
            MotionEvent.ACTION_POINTER_UP -> {
                // 追っている指が離れたら、そこで終える。ほかの指は無視する
                if (e.getPointerId(e.actionIndex) == pointerId) endGesture(e, cancelled = false)
            }
        }
        return true
    }

    private fun startGesture(e: MotionEvent) {
        // 前の操作の UP/CANCEL が届かなかったときの後始末
        if (dragging) { dragging = false; releaseDrag(cancelled = true) }
        pointerId = e.getPointerId(0)
        downX = e.x
        downY = e.y
        lastX = e.x
        ignoring = animating || controller.phase == PageController.Phase.ANIMATING
    }

    /** 水平のドラッグになったら始める。奪うなら true */
    private fun maybeStartDrag(e: MotionEvent): Boolean {
        if (gestureSwallowed()) return false
        val i = e.findPointerIndex(pointerId)
        if (i < 0) return false
        val x = e.getX(i)
        if (!decision.isHorizontalDrag(x - downX, e.getY(i) - downY, touchSlop)) return false
        if (!controller.beginDrag(monotonicMs())) return false
        dragging = true
        dragFrom = controller.settledPage
        setLayers(true)
        dragTo(x)
        return true
    }

    private fun dragTo(x: Float) {
        lastX = x
        val w = width.toFloat()
        if (w <= 0f) return
        val origin = if (dragFrom == Page.B) 1f else 0f
        position = rubberBand(origin - (x - downX) / w, w)
        applyPosition()
    }

    /** 範囲の外へは、ほんの少し(最大 [MAX_PULL_DP])しか動かさない */
    private fun rubberBand(raw: Float, w: Float): Float {
        val maxPull = MAX_PULL_DP * density / w
        return when {
            raw < 0f -> -minOf(-raw * PULL_RESISTANCE, maxPull)
            raw > 1f -> 1f + minOf((raw - 1f) * PULL_RESISTANCE, maxPull)
            else -> raw
        }
    }

    private fun endGesture(e: MotionEvent, cancelled: Boolean) {
        val wasDragging = dragging
        dragging = false
        ignoring = false
        if (!wasDragging) return
        val i = e.findPointerIndex(pointerId)
        if (i >= 0) lastX = e.getX(i)
        releaseDrag(cancelled)
    }

    private fun releaseDrag(cancelled: Boolean) {
        val outcome = if (cancelled) SwipeOutcome.REVERT else
            decision.decide(dragFrom, lastX - downX, velocity.pxPerSec(), width.toFloat(), density)
        val target = if (outcome == SwipeOutcome.ADVANCE) (if (dragFrom == Page.A) Page.B else Page.A) else dragFrom
        controller.endDrag(target, monotonicMs())
    }

    // ---- 移動のアニメーション ----

    private fun animateTo(target: Float) {
        removeCallbacks(frame)
        animating = true
        animFrom = position
        animTo = target
        animStartMs = monotonicMs()
        setLayers(true)
        if (animFrom == animTo) finishAnimation() else postOnAnimation(frame)
    }

    /** 1 フレーム進める。時刻は `monotonicMs`(テストでは仮想時計)から求める */
    internal fun stepAnimation() {
        if (!animating) return
        val t = ((monotonicMs() - animStartMs) / ANIMATION_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        position = animFrom + (animTo - animFrom) * eased
        applyPosition()
        if (t >= 1f) finishAnimation() else postOnAnimation(frame)
    }

    private fun finishAnimation() {
        removeCallbacks(frame)
        animating = false
        position = animTo
        applyPosition()
        setLayers(false)
        controller.animationEnded(monotonicMs())
    }

    /** アニメーションなしで置く(背面・前面への復帰)。進行中のアニメーションとドラッグは捨てる */
    private fun snapTo(target: Float) {
        removeCallbacks(frame)
        animating = false
        if (dragging) { dragging = false; ignoring = true }
        position = target
        applyPosition()
        setLayers(false)
    }

    /** アニメーション中だけハードウェアレイヤー(Echo Show 5 の GPU は非力) */
    private fun setLayers(on: Boolean) {
        val type = if (on) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (pageA.layerType != type) pageA.setLayerType(type, null)
        if (pageB.layerType != type) pageB.setLayerType(type, null)
    }

    private fun applyPosition() {
        val w = width.toFloat()
        pageA.translationX = -position * w
        pageB.translationX = (1f - position) * w
        // 見えないページは描かない・触れさせない
        val bVis = if (position > EPS) VISIBLE else INVISIBLE
        val aVis = if (position < 1f - EPS) VISIBLE else INVISIBLE
        if (pageB.visibility != bVis) pageB.visibility = bVis
        if (pageA.visibility != aVis) pageA.visibility = aVis
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyPosition()
        excludeEdgesFromSystemGestures(r - l, b - t)
    }

    /**
     * 左右の端を、システムのジェスチャ(戻る、バーの表示)から外す(設計書 §5.7, R-3)。
     * 端からのスワイプが効くかは実機でないと確かめられない。システムは端ごとに高さ約 200 dp までしか認めない
     */
    private fun excludeEdgesFromSystemGestures(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        runCatching {
            val edge = minOf((EDGE_EXCLUSION_DP * density).toInt(), w / 2)
            systemGestureExclusionRects = listOf(Rect(0, 0, edge, h), Rect(w - edge, 0, w, h))
        }
    }

    override fun onDetachedFromWindow() {
        // 落ち着かないまま外れても、コントローラーが「移動中」のままにならないように
        if (animating) finishAnimation()
        super.onDetachedFromWindow()
    }

    // ---- ページ点 ----

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (!dotsVisible) return
        val scale = minOf(width / PanelLayout.BASE_W, height / PanelLayout.BASE_H)
        if (scale <= 0f) return
        // A のボタン(下端 472、中央は 456 まで)と重ならない、画面下端の中央
        val cy = height / 2f + (DOT_BASE_Y - PanelLayout.BASE_H / 2f) * scale
        for (i in 0..1) {
            val near = (1f - abs(position - i)).coerceIn(0f, 1f)
            dotPaint.color = blend(PanelView.DISABLED, PanelView.ACCENT, near)
            c.drawCircle(width / 2f + (i - 0.5f) * DOT_GAP * scale, cy, DOT_RADIUS * scale, dotPaint)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * t).toInt()
        return Color.rgb(ch(Color.red(from), Color.red(to)), ch(Color.green(from), Color.green(to)), ch(Color.blue(from), Color.blue(to)))
    }

    companion object {
        const val ANIMATION_MS = 180L

        /** 端で引っ張られる最大(dp)と、指の動きに対する割合 */
        const val MAX_PULL_DP = 24f
        private const val PULL_RESISTANCE = 0.25f
        private const val EPS = 0.0005f

        /** システムのジェスチャから外す、左右の端の幅(dp) */
        const val EDGE_EXCLUSION_DP = 40f

        // 960×480 基準のページ点
        private const val DOT_BASE_Y = 468f
        private const val DOT_GAP = 22f
        private const val DOT_RADIUS = 4.5f
    }
}
