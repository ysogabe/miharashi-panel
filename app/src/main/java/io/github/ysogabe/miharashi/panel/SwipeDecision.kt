package io.github.ysogabe.miharashi.panel

import kotlin.math.abs

/** ページ送りの判定結果。 */
enum class SwipeOutcome { ADVANCE, REVERT }

/**
 * スワイプでページを送るかの判定(設計書 §5.7)。Android に依存しない純 Kotlin。
 *
 * - 水平のドラッグ: `|dx| > touchSlop` かつ `|dx| > 2|dy|`(斜めは水平とみなさない)
 * - 向き: A→B は左へ(dx < 0)、B→A は右へ(dx > 0)。逆向きは端の外なので元に戻す
 * - 確定: 進む向きの移動距離 ≥ 幅の 25%、または進む向きの速度 ≥ 600 dp/s(どちらも境界を含む)
 *
 * しきい値は呼び出し側から渡せる。
 */
class SwipeDecision(
    private val distanceFraction: Float = DEFAULT_DISTANCE_FRACTION,
    private val velocityDpPerSec: Float = DEFAULT_VELOCITY_DP_PER_SEC,
) {
    init {
        require(distanceFraction.isFinite() && distanceFraction > 0f)
        require(velocityDpPerSec.isFinite() && velocityDpPerSec > 0f)
    }

    /** `dx`, `dy` は DOWN からの移動量(px)。 */
    fun isHorizontalDrag(dx: Float, dy: Float, touchSlop: Float): Boolean =
        abs(dx) > touchSlop && abs(dx) > HORIZONTAL_RATIO * abs(dy)

    /** `from` のページから見て、ページ送りの向きの符号(A は左 = -1、B は右 = +1)。 */
    fun direction(from: Page): Int = if (from == Page.A) -1 else 1

    /**
     * 指を離したときの判定。`dx` は DOWN からの水平移動(px)、`velocityXPxPerSec` は離す直前の水平速度(px/s、右向きが正)、
     * `widthPx` はページの幅、`density` は dp への換算(px / density = dp)。
     * 進む向きに少しも動いていなければ、速度が大きくても元に戻す(向きを変えた指を送りにしない)。
     */
    fun decide(from: Page, dx: Float, velocityXPxPerSec: Float, widthPx: Float, density: Float): SwipeOutcome {
        if (!dx.isFinite() || !velocityXPxPerSec.isFinite() || !widthPx.isFinite() || !density.isFinite()) return SwipeOutcome.REVERT
        if (widthPx <= 0f || density <= 0f) return SwipeOutcome.REVERT
        val sign = direction(from)
        val forward = dx * sign
        if (forward <= 0f) return SwipeOutcome.REVERT
        if (forward >= widthPx * distanceFraction) return SwipeOutcome.ADVANCE
        val forwardDpPerSec = velocityXPxPerSec * sign / density
        return if (forwardDpPerSec >= velocityDpPerSec) SwipeOutcome.ADVANCE else SwipeOutcome.REVERT
    }

    companion object {
        const val DEFAULT_DISTANCE_FRACTION = 0.25f
        const val DEFAULT_VELOCITY_DP_PER_SEC = 600f
        const val HORIZONTAL_RATIO = 2f
    }
}

/**
 * 水平速度の見積もり。直近 [WINDOW_MS] ms の点だけで、最初と最後の傾きを取る。
 * 離す前に指が止まっていれば窓の中に点が 1 つしか残らず 0 になる(止めてから離した操作を、勢いのある操作にしない)。
 * `VelocityTracker` を使わないので、JVM の単体テストでも同じ値になる。
 */
class SwipeVelocity {
    private val times = LongArray(CAPACITY)
    private val xs = FloatArray(CAPACITY)
    private var count = 0

    fun clear() { count = 0 }

    fun add(timeMs: Long, x: Float) {
        if (count == CAPACITY) {
            System.arraycopy(times, 1, times, 0, CAPACITY - 1)
            System.arraycopy(xs, 1, xs, 0, CAPACITY - 1)
            count--
        }
        times[count] = timeMs
        xs[count] = x
        count++
    }

    /** px/s(右向きが正)。 */
    fun pxPerSec(): Float {
        if (count < 2) return 0f
        val last = count - 1
        var first = last
        while (first > 0 && times[last] - times[first - 1] <= WINDOW_MS) first--
        val dt = times[last] - times[first]
        if (dt <= 0L) return 0f
        return (xs[last] - xs[first]) * 1000f / dt
    }

    companion object {
        const val WINDOW_MS = 100L
        private const val CAPACITY = 16
    }
}
