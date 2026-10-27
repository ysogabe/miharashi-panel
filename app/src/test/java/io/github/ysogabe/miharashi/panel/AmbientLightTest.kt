package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class AmbientLightTest {
    private fun state(now: Long = 30_000, mode: String = "auto", on: Boolean = true, control: Boolean = true): PanelState =
        PanelState().withStatus(KioskStatus.parse(javaClass.getResource("/fixtures/status-display.json")!!.readText())
            .copy(display = DisplayInfo(on, mode, control)), now)

    @Test fun onChangeSensorNeedsNoSecondSampleToReachDeadline() {
        val policy = DarknessPolicy(1f)
        policy.observe(1f, 0)
        assertEquals(1L, policy.remainingMs(29_999))
        assertFalse(policy.takeRequest(state(), 29_999))
        assertTrue(policy.takeRequest(state(), 30_000))
        assertFalse(policy.takeRequest(state(90_000), 90_000))
        policy.observe(0f, 90_000)
        assertFalse(policy.takeRequest(state(120_000), 120_000))
    }

    @Test fun shortCoverAndThresholdNoiseRestartDurationAndRearmNeedsBrightness() {
        val policy = DarknessPolicy(1f)
        policy.observe(0f, 0)
        policy.observe(1.1f, 29_999)
        assertNull(policy.remainingMs(30_000))
        policy.observe(1f, 30_000)
        assertFalse(policy.takeRequest(state(59_999), 59_999))
        assertTrue(policy.takeRequest(state(60_000), 60_000))
        policy.observe(4.9f, 60_001)
        policy.observe(0f, 60_002)
        assertFalse(policy.takeRequest(state(100_000), 100_000))
        policy.observe(5f, 100_000)
        policy.observe(0f, 110_000)
        assertTrue(policy.takeRequest(state(140_000), 140_000))
    }

    @Test fun invalidSensorValuesAndPauseCancelContinuity() {
        for (invalid in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            val policy = DarknessPolicy(1f)
            policy.observe(0f, 0)
            policy.observe(invalid, 20_000)
            assertFalse(policy.takeRequest(state(), 30_000))
            policy.observe(0f, 30_000)
            policy.stopObserving()
            assertFalse(policy.takeRequest(state(100_000), 100_000))
            policy.observe(0f, 100_000)
            assertTrue(policy.takeRequest(state(130_000), 130_000))
        }
    }

    @Test fun manualActionsSuppressThisDarkEpisodeEvenAcrossPause() {
        val policy = DarknessPolicy(1f)
        policy.observe(0f, 0)
        policy.handled()
        policy.stopObserving()
        policy.observe(0f, 100_000)
        assertFalse(policy.takeRequest(state(130_000), 130_000))
        policy.observe(5f, 140_000)
        policy.observe(0f, 150_000)
        assertTrue(policy.takeRequest(state(180_000), 180_000))
    }

    @Test fun onlyFreshOnlineControllableAutoOnMonitorCanBeTurnedOff() {
        val policy = DarknessPolicy(1f)
        policy.observe(0f, 0)
        val states = listOf(PanelState(), state(0), state(24_999), state(mode = "force_on"),
            state(mode = "force_off"), state(on = false), state(control = false),
            state().withError("offline"), state().copy(status = state().status!!.copy(display = null)))
        states.forEach { assertFalse(policy.takeRequest(it, 30_000)) }
        // 復旧後の状態更新で初めて送れる。送信結果が不明でも同じ暗闇で二重送信しない。
        assertTrue(policy.takeRequest(state(), 30_000))
        assertFalse(policy.takeRequest(state(60_000), 60_000))
    }

    @Test fun configurableThresholdAlwaysHasAHigherRearmBoundary() {
        val policy = DarknessPolicy(100f)
        assertEquals(104f, policy.rearmLux)
        policy.observe(100f, 0)
        assertTrue(policy.takeRequest(state(), 30_000))
        policy.observe(103f, 40_000)
        policy.observe(0f, 50_000)
        assertFalse(policy.takeRequest(state(80_000), 80_000))
        policy.observe(104f, 90_000)
        policy.observe(0f, 100_000)
        assertTrue(policy.takeRequest(state(130_000), 130_000))
    }

    @Test fun brightnessIsBoundedMonotonicAndClockAlwaysHasPriority() {
        var previous = 0f
        for (lux in listOf(0f, 1f, 5f, 10f, 50f, 100f, 500f, Float.MAX_VALUE)) {
            val value = AmbientBrightness.value(lux, true, false)
            assertTrue(value in 0.05f..1f)
            assertTrue(value >= previous)
            previous = value
            assertEquals(0.05f, AmbientBrightness.value(lux, true, true))
        }
        for (lux in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(-1f, AmbientBrightness.value(lux, true, false))
            assertEquals(0.05f, AmbientBrightness.value(lux, true, true))
        }
        assertEquals(-1f, AmbientBrightness.value(500f, false, false))
        assertEquals(0.05f, AmbientBrightness.value(500f, false, true))
    }

    @Test fun luxInputRejectsNonFiniteAndOutOfRangeValues() {
        assertEquals(0f, Settings.parseDarkLux(" 0 "))
        assertEquals(1.5f, Settings.parseDarkLux("1.5"))
        assertEquals(100f, Settings.parseDarkLux("100"))
        listOf("NaN", "Infinity", "-1", "100.1", "1e100", "", "abc").forEach {
            assertNull(Settings.parseDarkLux(it))
        }
    }
}
