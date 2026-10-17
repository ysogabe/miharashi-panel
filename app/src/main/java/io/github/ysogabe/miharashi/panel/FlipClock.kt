package io.github.ysogabe.miharashi.panel

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 待機時計(docs/IDLE_CLOCK.md)のうち、Android に依存しない部分。
 * 時刻の読み方、フリップの進み方、画面内の移動と濃淡、次の更新までの間隔。描画は PanelView が Canvas で行う。
 * 時刻は呼び出し側が渡す(JVM の単体テストで確かめるため)。
 */
object ClockMath {
    const val MINUTE_MS = 60_000L
    const val MINUTES_PER_DAY = 24 * 60

    /** `epochMs` を `offsetMs`(その時刻のタイムゾーンの UTC からのずれ)で読んだ、現地の 0〜1439 分 */
    fun minuteOfDay(epochMs: Long, offsetMs: Long): Int =
        (Math.floorMod(Math.floorDiv(epochMs + offsetMs, MINUTE_MS), MINUTES_PER_DAY.toLong())).toInt()

    /** 時 2 桁・分 2 桁の 4 桁 */
    fun digits(minuteOfDay: Int): IntArray {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return intArrayOf(h / 10, h % 10, m / 10, m % 10)
    }

    /** 24 時間制の `HH:mm` */
    fun format(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** 現地の次の分の境目までのミリ秒(1〜60000) */
    fun msUntilNextMinute(epochMs: Long, offsetMs: Long): Long =
        MINUTE_MS - Math.floorMod(epochMs + offsetMs, MINUTE_MS)
}

/** 1 桁の今の見え方。`progress` が null なら止まっていて `to` を出す。0〜1 ならその桁は `from` から `to` へフリップ中 */
data class DigitView(val from: Int, val to: Int, val progress: Float?)

/** フリップ中の 2 枚の羽根の高さ。`top` は倒れていく上半分(`from`)、`bottom` は落ちてくる下半分(`to`)で、0 なら見えない、1 なら半分の全高 */
data class FlipFrame(val top: Float, val bottom: Float)

/**
 * 変わった桁だけを上下にフリップさせる時計の状態(4 桁: 時の十・一、分の十・一)。
 * 1 分進んだときだけフリップする。それ以外の変化(時刻合わせ、タイムゾーンや夏時間の変更、止まっていたあとの追いつき、
 * 最初の表示)は、途中のフリップを捨てて今の時刻を直接出す。
 * フリップの進み方は単調な `monoMs`(elapsedRealtime)で数える。壁時計の補正で止まったり飛んだりしない。
 */
class FlipClockModel {
    private val from = IntArray(4)
    private val to = IntArray(4)
    private val startMs = LongArray(4)
    private val flipping = BooleanArray(4)

    /** 今出している(フリップ中なら向かっている)分。まだ出していなければ -1 */
    var minute = -1
        private set
    private var lastEpochMs = 0L
    private var lastMonoMs = 0L

    /** 次の `update` で現在時刻を直接出す(待機に入った直後、前面への復帰など) */
    fun reset() {
        minute = -1
        flipping.fill(false)
    }

    /**
     * 現在の時刻を渡す。1 分進んでいれば変わった桁のフリップを始めて true。
     * 壁時計が単調な時計の進みと合わない(時刻合わせ)ときは、1 分進んでいてもフリップしない。
     */
    fun update(epochMs: Long, offsetMs: Long, monoMs: Long): Boolean {
        val next = ClockMath.minuteOfDay(epochMs, offsetMs)
        val discontinuous = minute < 0 ||
            monoMs < lastMonoMs ||
            abs((epochMs - lastEpochMs) - (monoMs - lastMonoMs)) > JUMP_TOLERANCE_MS
        lastEpochMs = epochMs
        lastMonoMs = monoMs
        if (next == minute) return false
        val advancedOne = minute >= 0 && !discontinuous &&
            next == (minute + 1) % ClockMath.MINUTES_PER_DAY
        val nd = ClockMath.digits(next)
        if (!advancedOne) {
            nd.copyInto(to)
            nd.copyInto(from)
            flipping.fill(false)
            minute = next
            return false
        }
        var started = false
        for (i in 0 until 4) {
            // 前のフリップが終わっていなければ、その桁は今の向かう先から続ける
            if (nd[i] != to[i]) {
                from[i] = to[i]
                to[i] = nd[i]
                startMs[i] = monoMs
                flipping[i] = true
                started = true
            }
        }
        minute = next
        return started
    }

    /** `i` 桁目(0〜3)の今の見え方 */
    fun view(i: Int, monoMs: Long): DigitView {
        if (!flipping[i]) return DigitView(to[i], to[i], null)
        val p = ((monoMs - startMs[i]).toFloat() / FLIP_MS).coerceIn(0f, 1f)
        return if (p >= 1f) DigitView(to[i], to[i], null) else DigitView(from[i], to[i], p)
    }

    /** どれかの桁がフリップ中か。終わった桁はここで片付ける */
    fun isFlipping(monoMs: Long): Boolean {
        var any = false
        for (i in 0 until 4) {
            if (!flipping[i]) continue
            if (monoMs - startMs[i] >= FLIP_MS || monoMs < startMs[i]) flipping[i] = false else any = true
        }
        return any
    }

    companion object {
        const val FLIP_MS = 450L

        /** 壁時計と単調な時計の進みの差がこれを超えたら、時刻が補正されたとみなす */
        const val JUMP_TOLERANCE_MS = 2_000L
    }
}

/** フリップの動き。上半分が手前へ倒れて(加速)、下半分が落ちてくる(減速) */
object FlipCurve {
    fun frame(progress: Float): FlipFrame {
        val p = progress.coerceIn(0f, 1f)
        return if (p < 0.5f) {
            val u = p * 2
            FlipFrame(cos(u * u * PI / 2).toFloat().coerceIn(0f, 1f), 0f)
        } else {
            val u = (p - 0.5f) * 2
            val rest = (1 - u) * (1 - u)
            FlipFrame(0f, sin((1 - rest) * PI / 2).toFloat().coerceIn(0f, 1f))
        }
    }
}

/**
 * 時計の大きさと、画面内を動ける範囲。960×480 を基準に、画面に合わせて拡大・縮小する(PanelLayout と同じ)。
 * 基準で時計全体が約 626×216(承認済みの画像に合わせた、カード 140×216・カードの間 9・コロン 48)、動く範囲は左右 48・上下 32。小さい画面では時計を縮めて、動いても画面から出ない範囲にする。
 * 座標は時計の左上を原点にした相対値で、置く位置は `originX` / `originY` + 動き。
 */
class ClockLayout(val width: Float, val height: Float) {
    val scale = minOf(width / BASE_W, height / BASE_H)

    /** 時計全体の大きさ。画面より大きくならない */
    private val fit = minOf(scale, width / CLOCK_W, height / CLOCK_H)
    val clockW = CLOCK_W * fit
    val clockH = CLOCK_H * fit
    val cardW = CARD_W * fit
    val cardH = CLOCK_H * fit
    val gap = GAP * fit
    val colonW = COLON_W * fit

    /** 動いていないときの時計の左上(画面の中央) */
    val originX = (width - clockW) / 2
    val originY = (height - clockH) / 2

    /** 動く範囲の半分: 中央から左右・上下にこれだけ。画面の端まで余白の範囲を超えない */
    val driftX = minOf(DRIFT_X * scale, originX).coerceAtLeast(0f)
    val driftY = minOf(DRIFT_Y * scale, originY).coerceAtLeast(0f)

    /** `i` 桁目(0〜3)のカードの左端(時計の左端から) */
    fun cardLeft(i: Int): Float = when (i) {
        0 -> 0f
        1 -> cardW + gap
        2 -> 2 * cardW + gap + colonW
        else -> 3 * cardW + 2 * gap + colonW
    }

    /** コロンの中心の x(時計の左端から) */
    val colonX = 2 * cardW + gap + colonW / 2

    companion object {
        const val BASE_W = 960f
        const val BASE_H = 480f
        const val CARD_W = 140f
        const val CLOCK_H = 216f
        const val GAP = 9f
        const val COLON_W = 48f
        const val CLOCK_W = 4 * CARD_W + 2 * GAP + COLON_W
        const val DRIFT_X = 48f
        const val DRIFT_Y = 32f
    }
}

/** 時計のゆっくりした移動と濃淡。単調な時計から決まるので、時刻の補正や再描画の間隔に左右されず、飛ばずにつながる */
object ClockDrift {
    /** 水平・垂直・濃淡の周期。互いに割り切れない長さにして、同じ軌道を繰り返さない */
    const val PERIOD_X_MS = 317_000L
    const val PERIOD_Y_MS = 211_000L
    const val PERIOD_TONE_MS = 463_000L

    /** 濃淡の下限と上限(1 が通常) */
    const val TONE_MIN = 0.86f
    const val TONE_MAX = 1f

    /** 中央からの水平のずれ。-`layout.driftX` 〜 `layout.driftX` */
    fun dx(layout: ClockLayout, monoMs: Long): Float = layout.driftX * wave(monoMs, PERIOD_X_MS, 0.0)

    /** 中央からの垂直のずれ。-`layout.driftY` 〜 `layout.driftY` */
    fun dy(layout: ClockLayout, monoMs: Long): Float = layout.driftY * wave(monoMs, PERIOD_Y_MS, 1.3)

    /** 数字とカードに掛ける明るさの係数。`TONE_MIN` 〜 `TONE_MAX` */
    fun tone(monoMs: Long): Float {
        val w = (wave(monoMs, PERIOD_TONE_MS, 2.1) + 1) / 2
        return TONE_MIN + (TONE_MAX - TONE_MIN) * w
    }

    private fun wave(monoMs: Long, periodMs: Long, phase: Double): Float =
        sin(2 * PI * (monoMs % periodMs) / periodMs + phase).toFloat()
}

/** 待機時計の次の更新までの間隔 */
object ClockSchedule {
    /** 動きと濃淡を進める間隔(フリップしていないあいだ) */
    const val IDLE_TICK_MS = 1_000L

    /** 分の境目を少し過ぎてから更新する余裕 */
    const val BOUNDARY_SLACK_MS = 20L

    /** フリップしていないとき、次に画面を更新するまでのミリ秒。分の境目は遅れずに拾う */
    fun idleDelayMs(epochMs: Long, offsetMs: Long): Long =
        minOf(IDLE_TICK_MS, ClockMath.msUntilNextMinute(epochMs, offsetMs) + BOUNDARY_SLACK_MS)
}
