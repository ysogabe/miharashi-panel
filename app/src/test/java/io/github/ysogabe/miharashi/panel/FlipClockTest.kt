package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlipClockTest {
    private val jst = 9 * 3_600_000L
    private val minute = 60_000L

    /** 現地(オフセット 0)の `h:m:s` の壁時計(ミリ秒) */
    private fun at(h: Int, m: Int, s: Int = 0) = ((h * 60L + m) * 60 + s) * 1_000

    /** 1 桁ごとに今の見え方を取り出す */
    private fun FlipClockModel.shown(mono: Long) = IntArray(4) { view(it, mono).to }
    private fun FlipClockModel.flippingDigits(mono: Long) = (0 until 4).filter { view(it, mono).progress != null }

    // ---- 時刻の読み方 ----

    @Test
    fun minuteOfDayUsesTheLocalOffset() {
        // UTC 15:00 は JST の翌日 0:00
        assertEquals(0, ClockMath.minuteOfDay(at(15, 0), jst))
        assertEquals(9 * 60, ClockMath.minuteOfDay(0L, jst))
        assertEquals(21 * 60 + 48, ClockMath.minuteOfDay(at(21, 48, 59) + 999, 0L))
        // 秒は切り捨て: 59.999 秒は前の分
        assertEquals(23 * 60 + 59, ClockMath.minuteOfDay(at(23, 59, 59) + 999, 0L))
        assertEquals(0, ClockMath.minuteOfDay(at(24, 0), 0L))
    }

    @Test
    fun minuteOfDayHandlesNegativeEpochAndOffset() {
        // 1969-12-31 23:59 UTC
        assertEquals(23 * 60 + 59, ClockMath.minuteOfDay(-minute, 0L))
        // 1970-01-01 00:00 UTC の米国東部標準時(-5h)は前日 19:00
        assertEquals(19 * 60, ClockMath.minuteOfDay(0L, -5 * 3_600_000L))
        // 1 分ずらしたオフセットでも分で読む
        assertEquals(60 + 30, ClockMath.minuteOfDay(0L, 90 * minute))
    }

    @Test
    fun digitsAndFormat() {
        assertArrayEquals(intArrayOf(2, 1, 4, 8), ClockMath.digits(21 * 60 + 48))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ClockMath.digits(0))
        assertArrayEquals(intArrayOf(2, 3, 5, 9), ClockMath.digits(23 * 60 + 59))
        assertEquals("21:48", ClockMath.format(21 * 60 + 48))
        assertEquals("00:05", ClockMath.format(5))
        assertEquals("09:07", ClockMath.format(9 * 60 + 7))
    }

    @Test
    fun msUntilNextMinute() {
        assertEquals(60_000L, ClockMath.msUntilNextMinute(at(12, 0), 0L))
        assertEquals(1L, ClockMath.msUntilNextMinute(at(12, 0, 59) + 999, 0L))
        assertEquals(30_000L, ClockMath.msUntilNextMinute(at(12, 0, 30), jst))
        assertEquals(60_000L, ClockMath.msUntilNextMinute(-minute, 0L))
    }

    // ---- フリップするか、直接出すか ----

    @Test
    fun firstUpdateShowsTheTimeWithoutFlipping() {
        val m = FlipClockModel()
        assertFalse(m.update(at(21, 48, 10), 0L, 1_000))
        assertEquals(listOf(2, 1, 4, 8), m.shown(1_000).toList())
        assertTrue(m.flippingDigits(1_000).isEmpty())
        assertFalse(m.isFlipping(1_000))
    }

    @Test
    fun sameMinuteDoesNothing() {
        val m = FlipClockModel()
        m.update(at(21, 48, 10), 0L, 0)
        assertFalse(m.update(at(21, 48, 40), 0L, 30_000))
        assertFalse(m.isFlipping(30_000))
    }

    @Test
    fun onlyTheChangedDigitFlips() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        assertTrue(m.update(at(21, 49, 0), 0L, 1_000))
        assertEquals(listOf(3), m.flippingDigits(1_000))
        val v = m.view(3, 1_000)
        assertEquals(8, v.from)
        assertEquals(9, v.to)
        // 変わらない桁は止まったまま
        assertEquals(DigitView(2, 2, null), m.view(0, 1_000))
        assertEquals(DigitView(4, 4, null), m.view(2, 1_000))
    }

    @Test
    fun carryFlipsEveryChangedDigitIncludingWholeDay() {
        val m = FlipClockModel()
        m.update(at(9, 59, 59), 0L, 0)
        assertTrue(m.update(at(10, 0, 0), 0L, 1_000))
        assertEquals(listOf(0, 1, 2, 3), m.flippingDigits(1_000))

        val n = FlipClockModel()
        n.update(at(12, 39, 59), 0L, 0)
        assertTrue(n.update(at(12, 40, 0), 0L, 1_000))
        assertEquals(listOf(2, 3), n.flippingDigits(1_000))

        // 23:59 → 00:00 は日をまたぐので、1 分進んだものとして全桁フリップ
        val w = FlipClockModel()
        w.update(at(23, 59, 59), 0L, 0)
        assertTrue(w.update(at(24, 0, 0), 0L, 1_000))
        assertEquals(listOf(0, 1, 2, 3), w.flippingDigits(1_000))
        assertEquals(listOf(0, 0, 0, 0), w.shown(1_000).toList())
        assertEquals(2, w.view(0, 1_000).from)
    }

    @Test
    fun flipProgressRunsForOneFlipDurationThenSettles() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        m.update(at(21, 49, 0), 0L, 1_000)
        assertEquals(0f, m.view(3, 1_000).progress!!, 0.0001f)
        assertEquals(0.5f, m.view(3, 1_000 + 225).progress!!, 0.0001f)
        assertNotNull(m.view(3, 1_000 + 449).progress)
        assertTrue(m.isFlipping(1_000 + 449))
        assertNull(m.view(3, 1_000 + 450).progress)
        assertFalse(m.isFlipping(1_000 + 450))
        assertEquals(DigitView(9, 9, null), m.view(3, 5_000))
    }

    @Test
    fun jumpsAndBackwardMovesShowTheTimeDirectly() {
        // 大きく進めた補正
        val a = FlipClockModel()
        a.update(at(21, 48), 0L, 0)
        assertFalse(a.update(at(21, 52), 0L, 1_000))
        assertEquals(listOf(2, 1, 5, 2), a.shown(1_000).toList())
        assertFalse(a.isFlipping(1_000))

        // 戻した補正
        val b = FlipClockModel()
        b.update(at(21, 48), 0L, 0)
        assertFalse(b.update(at(21, 47), 0L, 1_000))
        assertEquals(listOf(2, 1, 4, 7), b.shown(1_000).toList())
        assertFalse(b.isFlipping(1_000))
    }

    @Test
    fun wallClockStepOfExactlyOneMinuteIsNotAFlip() {
        // 単調な時計は 1 秒しか進んでいないのに、壁時計が 1 分進んだ(時刻合わせ)
        val m = FlipClockModel()
        m.update(at(21, 48, 30), 0L, 0)
        assertFalse(m.update(at(21, 49, 30), 0L, 1_000))
        assertEquals(listOf(2, 1, 4, 9), m.shown(1_000).toList())
        assertFalse(m.isFlipping(1_000))
    }

    @Test
    fun smallClockSlewStillFlipsOnTheMinute() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        // 壁時計が 300ms 余分に進んだ程度は補正ではない
        assertTrue(m.update(at(21, 49, 0) + 300, 0L, 1_000))
        assertEquals(listOf(3), m.flippingDigits(1_000))
    }

    @Test
    fun timeZoneOrDstChangeShowsTheTimeDirectly() {
        val m = FlipClockModel()
        m.update(at(12, 0, 30), 0L, 0)
        // 同じ時刻のままオフセットだけ +1 時間(夏時間・タイムゾーンの変更)
        assertFalse(m.update(at(12, 0, 31), 60 * minute, 1_000))
        assertEquals(listOf(1, 3, 0, 0), m.shown(1_000).toList())
        assertFalse(m.isFlipping(1_000))
    }

    @Test
    fun jumpWhileFlippingCancelsTheFlip() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        m.update(at(21, 49, 0), 0L, 1_000)
        assertTrue(m.isFlipping(1_100))
        // フリップの途中で時刻が 3 時間進められた
        assertFalse(m.update(at(0, 49, 0) + 3 * 3_600_000L, 0L, 1_200))
        assertFalse(m.isFlipping(1_200))
        assertEquals(listOf(0, 3, 4, 9), m.shown(1_200).toList())
        assertTrue(m.flippingDigits(1_200).isEmpty())
    }

    @Test
    fun monotonicClockGoingBackwardsShowsTheTimeDirectly() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 10_000)
        assertFalse(m.update(at(21, 49, 0), 0L, 500))
        assertFalse(m.isFlipping(500))
        assertEquals(listOf(2, 1, 4, 9), m.shown(500).toList())
    }

    @Test
    fun resetShowsTheCurrentTimeNextTime() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        m.update(at(21, 49, 0), 0L, 1_000)
        m.reset()
        assertFalse(m.update(at(21, 49, 1), 0L, 1_100))
        assertFalse(m.isFlipping(1_100))
        assertEquals(listOf(2, 1, 4, 9), m.shown(1_100).toList())
        // 前面に戻ったとき、背面だった間の分は連続フリップにしない
        m.reset()
        assertFalse(m.update(at(21, 55), 0L, 400_000))
        assertTrue(m.flippingDigits(400_000).isEmpty())
    }

    @Test
    fun consecutiveMinutesFlipAgain() {
        val m = FlipClockModel()
        m.update(at(21, 48, 59), 0L, 0)
        assertTrue(m.update(at(21, 49, 0), 0L, 1_000))
        assertFalse(m.isFlipping(2_000))
        assertTrue(m.update(at(21, 50, 0), 0L, 61_000))
        assertEquals(listOf(2, 3), m.flippingDigits(61_000))
        assertEquals(DigitView(4, 5, 0f), m.view(2, 61_000))
        assertEquals(DigitView(9, 0, 0f), m.view(3, 61_000))
    }

    // ---- フリップの形 ----

    @Test
    fun flipCurveFallsThenLands() {
        val start = FlipCurve.frame(0f)
        assertEquals(1f, start.top, 0.0001f)
        assertEquals(0f, start.bottom, 0.0001f)
        val mid = FlipCurve.frame(0.5f)
        assertEquals(0f, mid.top, 0.001f)
        assertEquals(0f, mid.bottom, 0.001f)
        val end = FlipCurve.frame(1f)
        assertEquals(0f, end.top, 0.0001f)
        assertEquals(1f, end.bottom, 0.0001f)
        // 前半は上だけ、後半は下だけ。どちらも一方向に動く
        var lastTop = 1f
        var lastBottom = 0f
        for (i in 0..100) {
            val f = FlipCurve.frame(i / 100f)
            assertTrue(f.top in 0f..1f && f.bottom in 0f..1f)
            if (i < 50) assertEquals(0f, f.bottom, 0f) else assertEquals(0f, f.top, 0f)
            assertTrue(f.top <= lastTop + 1e-6f)
            assertTrue(f.bottom >= lastBottom - 1e-6f)
            lastTop = f.top
            lastBottom = f.bottom
        }
        // 範囲外の進み具合も安全
        assertEquals(1f, FlipCurve.frame(-1f).top, 0.0001f)
        assertEquals(1f, FlipCurve.frame(2f).bottom, 0.0001f)
    }

    // ---- 配置と移動 ----

    @Test
    fun layoutAtBaseSizeMatchesTheDesign() {
        val k = ClockLayout(960f, 480f)
        assertEquals(626f, k.clockW, 0.01f)
        assertEquals(216f, k.clockH, 0.01f)
        assertEquals(140f, k.cardW, 0.01f)
        assertEquals(216f, k.cardH, 0.01f)
        assertEquals(9f, k.gap, 0.01f)
        assertEquals(48f, k.colonW, 0.01f)
        assertEquals(48f, k.driftX, 0.01f)
        assertEquals(32f, k.driftY, 0.01f)
        // 4 桁が時計の幅の中に順に並び、コロンは 2 桁目と 3 桁目のあいだ
        for (i in 0 until 4) assertTrue(k.cardLeft(i) >= 0f && k.cardLeft(i) + k.cardW <= k.clockW + 0.01f)
        assertEquals(k.clockW, k.cardLeft(3) + k.cardW, 0.01f)
        for (i in 0 until 3) assertTrue(k.cardLeft(i) + k.cardW <= k.cardLeft(i + 1))
        assertTrue(k.colonX in (k.cardLeft(1) + k.cardW)..k.cardLeft(2))
        // 中央に置く
        assertEquals(167f, k.originX, 0.01f)
        assertEquals(132f, k.originY, 0.01f)
    }

    @Test
    fun clockStaysOnScreenForAnySizeAtAnyTime() {
        val sizes = listOf(
            960f to 480f, 1280f to 800f, 1920f to 1080f, 480f to 240f, 300f to 150f,
            400f to 400f, 200f to 480f, 960f to 100f, 1f to 1f, 0f to 0f,
        )
        for ((w, h) in sizes) {
            val k = ClockLayout(w, h)
            var t = 0L
            while (t < 3 * ClockDrift.PERIOD_X_MS * 2) {
                val x = k.originX + ClockDrift.dx(k, t)
                val y = k.originY + ClockDrift.dy(k, t)
                val eps = 0.001f
                assertTrue("$w x $h: left at $t was $x", x >= -eps)
                assertTrue("$w x $h: right at $t", x + k.clockW <= w + eps)
                assertTrue("$w x $h: top at $t was $y", y >= -eps)
                assertTrue("$w x $h: bottom at $t", y + k.clockH <= h + eps)
                t += 7_919
            }
        }
    }

    @Test
    fun driftCoversTheRangeAndStaysWithinTheDesignLimits() {
        val k = ClockLayout(960f, 480f)
        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f
        var t = 0L
        while (t < 3_600_000L) {
            val dx = ClockDrift.dx(k, t)
            val dy = ClockDrift.dy(k, t)
            assertTrue(abs(dx) <= 48f + 0.001f && abs(dy) <= 32f + 0.001f)
            minX = minOf(minX, dx); maxX = maxOf(maxX, dx)
            minY = minOf(minY, dy); maxY = maxOf(maxY, dy)
            t += 1_000
        }
        // 実際に動く(焼き付き対策になる)
        assertTrue(maxX - minX > 90f)
        assertTrue(maxY - minY > 60f)
    }

    @Test
    fun driftIsContinuousAtOneSecondSteps() {
        val k = ClockLayout(960f, 480f)
        var t = 0L
        while (t < 700_000L) {
            val step = abs(ClockDrift.dx(k, t + 1_000) - ClockDrift.dx(k, t))
            val stepY = abs(ClockDrift.dy(k, t + 1_000) - ClockDrift.dy(k, t))
            assertTrue("急に飛ぶ: dx $step at $t", step < 1.5f)
            assertTrue("急に飛ぶ: dy $stepY at $t", stepY < 1.5f)
            assertTrue(abs(ClockDrift.tone(t + 1_000) - ClockDrift.tone(t)) < 0.01f)
            t += 1_000
        }
    }

    @Test
    fun toneStaysInASubtleRangeAndVaries() {
        var lo = 2f
        var hi = 0f
        var t = 0L
        while (t < 2 * ClockDrift.PERIOD_TONE_MS) {
            val v = ClockDrift.tone(t)
            assertTrue(v in ClockDrift.TONE_MIN..ClockDrift.TONE_MAX)
            lo = minOf(lo, v); hi = maxOf(hi, v)
            t += 1_000
        }
        assertTrue(hi - lo > 0.1f)
        assertTrue(ClockDrift.TONE_MIN >= 0.8f)
    }

    // ---- 更新の予約 ----

    @Test
    fun idleDelayIsAtMostTheDriftTickAndNeverMissesTheMinute() {
        // 分の途中は低頻度の間隔
        assertEquals(ClockSchedule.IDLE_TICK_MS, ClockSchedule.idleDelayMs(at(12, 0, 10), 0L))
        // 境目の 300ms 手前なら、境目の直後に起きる
        assertEquals(300L + ClockSchedule.BOUNDARY_SLACK_MS, ClockSchedule.idleDelayMs(at(12, 0, 59) + 700, 0L))
        // 境目ちょうどは、次の 1 分先ではなく低頻度の間隔
        assertEquals(ClockSchedule.IDLE_TICK_MS, ClockSchedule.idleDelayMs(at(12, 1, 0), 0L))
        for (s in 0 until 60) {
            val epoch = at(12, 0, s) + 123
            val d = ClockSchedule.idleDelayMs(epoch, jst)
            assertTrue(d in 1..ClockSchedule.IDLE_TICK_MS)
            // 予約した時刻には、次の分に入っている(取りこぼさない)か、まだ入っていなければ次の予約がある
            val after = ClockMath.minuteOfDay(epoch + d, jst)
            val before = ClockMath.minuteOfDay(epoch, jst)
            if (d < ClockSchedule.IDLE_TICK_MS) assertEquals((before + 1) % 1440, after)
        }
    }
}
