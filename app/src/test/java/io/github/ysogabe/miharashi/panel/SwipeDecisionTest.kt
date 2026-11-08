package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class SwipeDecisionTest {
    private val d = SwipeDecision()
    private val w = 960f
    private fun decide(from: Page, dx: Float, v: Float = 0f, density: Float = 1f) = d.decide(from, dx, v, w, density)

    @Test fun distanceBoundaryIsInclusiveAtQuarterOfWidth() {
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, -239.9f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.A, -240f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.A, -500f))
        assertEquals(SwipeOutcome.REVERT, decide(Page.B, 239.9f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.B, 240f))
    }

    @Test fun velocityBoundaryIsInclusiveAt600DpPerSecond() {
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, -10f, -599.9f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.A, -10f, -600f))
        assertEquals(SwipeOutcome.REVERT, decide(Page.B, 10f, 599.9f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.B, 10f, 600f))
    }

    @Test fun velocityIsMeasuredInDpNotPixels() {
        // 密度 2 なら 1200 px/s が 600 dp/s
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, -10f, -1199f, density = 2f))
        assertEquals(SwipeOutcome.ADVANCE, decide(Page.A, -10f, -1200f, density = 2f))
    }

    @Test fun wrongDirectionNeverAdvances() {
        // A で右へ、B で左へは、端の外
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, 500f, 5000f))
        assertEquals(SwipeOutcome.REVERT, decide(Page.B, -500f, -5000f))
        // 動きは進む向きでも、離す直前に逆向きへ弾いたなら送らない
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, -100f, 3000f))
        // 進む向きに少しも動いていなければ、速度が大きくても戻す
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, 0f, -3000f))
        assertEquals(SwipeOutcome.REVERT, decide(Page.A, 20f, -3000f))
    }

    @Test fun customThresholdsAreHonoured() {
        val strict = SwipeDecision(distanceFraction = 0.5f, velocityDpPerSec = 1000f)
        assertEquals(SwipeOutcome.REVERT, strict.decide(Page.A, -479f, 0f, w, 1f))
        assertEquals(SwipeOutcome.ADVANCE, strict.decide(Page.A, -480f, 0f, w, 1f))
        assertEquals(SwipeOutcome.REVERT, strict.decide(Page.A, -10f, -999f, w, 1f))
        assertEquals(SwipeOutcome.ADVANCE, strict.decide(Page.A, -10f, -1000f, w, 1f))
    }

    @Test fun degenerateInputsRevert() {
        assertEquals(SwipeOutcome.REVERT, d.decide(Page.A, -500f, -5000f, 0f, 1f))
        assertEquals(SwipeOutcome.REVERT, d.decide(Page.A, -500f, -5000f, w, 0f))
        assertEquals(SwipeOutcome.REVERT, d.decide(Page.A, Float.NaN, -5000f, w, 1f))
        assertEquals(SwipeOutcome.REVERT, d.decide(Page.A, -500f, Float.NaN, w, 1f))
        assertEquals(SwipeOutcome.REVERT, d.decide(Page.A, -500f, Float.POSITIVE_INFINITY, w, 1f))
    }

    @Test fun horizontalNeedsSlopAndTwiceTheVerticalMovement() {
        val slop = 8f
        assertFalse(d.isHorizontalDrag(8f, 0f, slop))     // slop ちょうどは超えていない
        assertTrue(d.isHorizontalDrag(8.1f, 0f, slop))
        assertTrue(d.isHorizontalDrag(-100f, 49f, slop))
        assertFalse("|dx| = 2|dy| は斜め", d.isHorizontalDrag(100f, 50f, slop))
        assertFalse(d.isHorizontalDrag(100f, -60f, slop))
        assertFalse("縦の動き", d.isHorizontalDrag(5f, 200f, slop))
    }

    @Test fun directionIsLeftFromAAndRightFromB() {
        assertEquals(-1, d.direction(Page.A))
        assertEquals(1, d.direction(Page.B))
    }

    @Test fun invalidThresholdsAreRejected() {
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { SwipeDecision(distanceFraction = bad) }
            assertThrows(IllegalArgumentException::class.java) { SwipeDecision(velocityDpPerSec = bad) }
        }
    }

    @Test fun velocityUsesOnlyTheLastWindowAndIsZeroWhenHeld() {
        val v = SwipeVelocity()
        assertEquals(0f, v.pxPerSec(), 0f)
        v.add(0, 500f)
        assertEquals("1 点では求まらない", 0f, v.pxPerSec(), 0f)
        v.add(20, 460f); v.add(40, 420f); v.add(60, 380f)
        assertEquals(-2000f, v.pxPerSec(), 0.01f)
        // 止まってから離した: 最後の点が窓の外の点から離れていれば 0
        v.add(500, 380f)
        assertEquals(0f, v.pxPerSec(), 0f)
        v.clear()
        assertEquals(0f, v.pxPerSec(), 0f)
        // 同時刻の点だけなら 0 で割らない
        v.add(10, 0f); v.add(10, 100f)
        assertEquals(0f, v.pxPerSec(), 0f)
    }

    @Test fun velocityWindowIgnoresOldSlowPart() {
        val v = SwipeVelocity()
        v.add(0, 1000f); v.add(250, 990f)           // ゆっくり
        v.add(320, 940f); v.add(340, 880f); v.add(360, 820f)  // 最後は速い
        assertEquals(-3000f, v.pxPerSec(), 0.01f)
    }
}
