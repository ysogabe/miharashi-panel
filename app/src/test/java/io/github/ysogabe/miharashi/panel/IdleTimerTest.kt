package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimerTest {
    @Test
    fun sleepsAfterTimeoutAndWakesOnTouch() {
        val t = IdleTimer(timeoutMs = 180_000, nowMs = 0)
        assertFalse(t.isSleeping(179_999))
        assertTrue("ちょうど 3 分で消灯", t.isSleeping(180_000))
        t.touch(200_000)
        assertFalse(t.isSleeping(200_000))
        assertEquals(30_000L, t.untilSleepMs(350_000))
        assertTrue(t.isSleeping(380_000))
        assertEquals(0L, t.untilSleepMs(999_999))
    }

    @Test
    fun zeroNeverSleeps() {
        val t = IdleTimer(timeoutMs = 0, nowMs = 0)
        assertFalse(t.isSleeping(Long.MAX_VALUE / 2))
        assertNull(t.untilSleepMs(1_000))
    }

    @Test
    fun sleepMinutesInput() {
        assertEquals(3, Settings.parseSleepMinutes(" 3 "))
        assertEquals(0, Settings.parseSleepMinutes("0"))
        assertEquals(240, Settings.parseSleepMinutes("240"))
        assertNull(Settings.parseSleepMinutes("241"))
        assertNull(Settings.parseSleepMinutes("-1"))
        assertNull(Settings.parseSleepMinutes("1.5"))
        assertNull(Settings.parseSleepMinutes(""))
    }
}
