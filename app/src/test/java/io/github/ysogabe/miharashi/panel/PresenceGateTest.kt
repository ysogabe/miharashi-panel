package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC9: 照度による在席ゲート(`AmbientLightGate` + `LuxDarkTracker`)。 */
class PresenceGateTest {
    private var lux: Float? = null
    private var cfg = GateConfig(darkLux = 1f, darkSeconds = 30)
    private val gate = AmbientLightGate({ LuxSample(lux) }, { cfg })

    /** センサーの値の到着(on-change 型)。 */
    private fun sensor(v: Float?, now: Long) {
        lux = v
        gate.observe(v, now)
    }

    private fun at(now: Long) = gate.evaluate(now)

    @Test fun brightIsPresent() {
        sensor(300f, 0)
        val v = at(1_000_000)
        assertEquals(Presence.PRESENT, v.presence)
        assertEquals("bright", v.reason)
        assertEquals(300f, v.lux)
        assertNull(v.darkForMs)
    }

    @Test fun luxUnavailableIsUnknown() {
        for (bad in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            sensor(bad, 0)
            val v = at(100_000)
            assertEquals("$bad", Presence.UNKNOWN, v.presence)
            assertEquals("lux_unavailable", v.reason)
        }
    }

    @Test fun beforeAnyValueIsUnknown() {
        assertEquals(Presence.UNKNOWN, at(0).presence)
    }

    @Test fun darkness29_30_31Seconds() {
        sensor(0.5f, 10_000)
        assertEquals(Presence.PRESENT, at(10_000).presence) // 暗くなった直後
        val v29 = at(39_000)
        assertEquals("29 秒では PRESENT", Presence.PRESENT, v29.presence)
        assertEquals(29_000L, v29.darkForMs)
        assertEquals(Presence.PRESENT, at(39_999).presence)
        val v30 = at(40_000)
        assertEquals("30 秒ちょうどで ABSENT", Presence.ABSENT, v30.presence)
        assertEquals("dark", v30.reason)
        assertEquals(30_000L, v30.darkForMs)
        assertEquals(0.5f, v30.lux)
        assertEquals(Presence.ABSENT, at(41_000).presence)
    }

    @Test fun thresholdIsInclusive() {
        sensor(1f, 0)
        assertEquals(Presence.ABSENT, at(30_000).presence)
        sensor(1.01f, 40_000)
        assertEquals(Presence.PRESENT, at(100_000).presence)
    }

    @Test fun zeroSecondsIsAbsentImmediatelyAtThreshold() {
        cfg = GateConfig(1f, 0)
        sensor(2f, 0)
        assertEquals(Presence.PRESENT, at(0).presence)
        sensor(1f, 1_000)
        assertEquals(Presence.ABSENT, at(1_000).presence)
    }

    @Test fun brighteningDiscardsDarkStart() {
        sensor(0f, 0)
        sensor(50f, 20_000)
        sensor(0f, 25_000)
        assertEquals("暗闇は 25 秒から数え直し", Presence.PRESENT, at(54_999).presence)
        assertEquals(Presence.ABSENT, at(55_000).presence)
    }

    @Test fun nullOrInvalidLuxDiscardsDarkStart() {
        for (bad in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            sensor(0f, 0)
            sensor(bad, 20_000)
            sensor(0f, 25_000)
            assertEquals("$bad", Presence.PRESENT, at(54_999).presence)
            assertEquals("$bad", Presence.ABSENT, at(55_000).presence)
            sensor(100f, 60_000) // 次のケースのために明るくする
        }
    }

    @Test fun staysAbsentWithoutNewSensorEvents() {
        sensor(0f, 0)
        assertEquals(Presence.ABSENT, at(3_600_000).presence) // on-change 型: イベントが来なくても時刻で判定
    }

    @Test fun resetDiscardsDarkStartAndLastLux() {
        sensor(0f, 0)
        assertEquals(Presence.ABSENT, at(30_000).presence)
        gate.reset() // onPause
        lux = null // 復帰後の最初の値は未着
        assertEquals(Presence.UNKNOWN, at(100_000).presence)
        sensor(0f, 100_000)
        assertEquals("背面の時間は継続に含めない", Presence.PRESENT, at(129_999).presence)
        assertEquals(Presence.ABSENT, at(130_000).presence)
    }

    @Test fun changingThresholdOrSecondsRestartsCounting() {
        sensor(0.5f, 0)
        assertEquals(Presence.ABSENT, at(30_000).presence)
        cfg = GateConfig(darkLux = 0.8f, darkSeconds = 30) // 閾値の変更
        val v = at(31_000)
        assertEquals(Presence.PRESENT, v.presence)
        assertEquals("設定を変えた時刻から数え直す", 0L, v.darkForMs)
        assertEquals(Presence.PRESENT, at(60_999).presence)
        assertEquals(Presence.ABSENT, at(61_000).presence)

        cfg = GateConfig(darkLux = 0.8f, darkSeconds = 10) // 秒数の変更も同じ
        assertEquals(Presence.PRESENT, at(70_000).presence)
        assertEquals(Presence.ABSENT, at(80_000).presence)
    }

    @Test fun thresholdChangeMakingLuxBrightDoesNotCount() {
        sensor(0.5f, 0)
        cfg = GateConfig(darkLux = 0.1f, darkSeconds = 30) // 0.5 はもう暗闇ではない
        val v = at(100_000)
        assertEquals(Presence.PRESENT, v.presence)
        assertNull(v.darkForMs)
    }

    @Test fun independentOfDarknessPolicyHandled() {
        val policy = DarknessPolicy(1f)
        policy.observe(0f, 0)
        policy.handled() // 消灯を送った/手動操作した
        assertNull(policy.remainingMs(30_000))
        sensor(0f, 0)
        assertEquals("handled でもゲートは暗闇の継続を返す", Presence.ABSENT, at(30_000).presence)
        assertEquals(30_000L, at(30_000).darkForMs)
        policy.observe(0f, 30_000)
        assertEquals(Presence.ABSENT, at(60_000).presence)
    }

    @Test fun trackerAlone() {
        val t = LuxDarkTracker(GateConfig(1f, 30))
        assertNull(t.darkDurationMs(0))
        t.observe(0f, 5)
        assertEquals(10L, t.darkDurationMs(15))
        t.observe(0.2f, 100) // 暗闇の継続中は開始時刻を保つ
        assertEquals(95L, t.darkDurationMs(100))
        t.reset()
        assertNull(t.darkDurationMs(200))
    }

    @Test fun configRejectsInvalidValues() {
        for (f in listOf({ GateConfig(Float.NaN, 30) }, { GateConfig(-1f, 30) }, { GateConfig(1f, -1) })) {
            assertTrue(runCatching { f() }.isFailure)
        }
    }
}
