package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC9(ゲートとの結線)/ AC10 / AC18 / AC19 と、Defer・onSettled。 */
class AutoSwitchPolicyTest {
    private val minute = 60_000L

    /** ダミーのゲート(AC10: 本体のコードを変えずに差し込める)。 */
    private class FakeGate(var presence: Presence = Presence.PRESENT) : PresenceGate {
        var calls = 0
        override fun evaluate(nowMs: Long): GateVerdict {
            calls++
            return GateVerdict(presence, "fake")
        }
    }

    private val gate = FakeGate()
    private val policy = AutoSwitchPolicy(gate)

    private fun notice(id: String = "n1", age: Long = 3, low: Boolean = false) = DaidaiNotice(
        id, "title", "body", age, if (low) NoticePriority.LOW else NoticePriority.NORMAL,
    )

    private fun cand(at: Long, id: String = "n1", age: Long = 3, low: Boolean = false) =
        NoticeCandidate(notice(id, age, low), receivedAtMs = at, rev = 2)

    private fun ctx(
        now: Long,
        enabled: Boolean = true,
        listen: Boolean = true,
        onB: Boolean = false,
        busy: Boolean = false,
    ) = AutoSwitchContext(now, enabled, listen, onB, busy)

    // --- AC10 / AC9: ゲートが PRESENT のときだけ切り替える ---

    @Test fun switchesOnlyWhenPresent() {
        val r = policy.evaluate(cand(0), ctx(0))
        assertEquals(AutoSwitchAction.SWITCH, r.action)
        assertEquals(AutoSwitchReason.PRESENT, r.reason)
        assertEquals("fake", r.verdict?.reason)

        gate.presence = Presence.ABSENT
        val a = policy.evaluate(cand(0, "n2"), ctx(0))
        assertEquals(AutoSwitchAction.SKIP, a.action)
        assertEquals(AutoSwitchReason.GATE_ABSENT, a.reason)
        assertFalse(a.openMic)

        gate.presence = Presence.UNKNOWN
        val u = policy.evaluate(cand(0, "n3"), ctx(0))
        assertEquals(AutoSwitchAction.SKIP, u.action)
        assertEquals(AutoSwitchReason.GATE_UNKNOWN, u.reason)
    }

    @Test fun skippedByGateIsNotReevaluatedLater() {
        gate.presence = Presence.ABSENT
        policy.evaluate(cand(0), ctx(0))
        assertFalse(policy.hasPending)
        gate.presence = Presence.PRESENT
        assertNull(policy.onSettled(ctx(1_000)))
    }

    @Test fun realAmbientLightGateWorksWithPolicy() {
        var lux: Float? = 0f
        val cfg = GateConfig(1f, 30)
        val g = AmbientLightGate({ LuxSample(lux) }, { cfg })
        val p = AutoSwitchPolicy(g)
        g.observe(0f, 0)
        assertEquals(AutoSwitchAction.SWITCH, p.evaluate(cand(29_000), ctx(29_000)).action) // 29 秒では切り替える
        val r = p.evaluate(cand(30_000, "n2"), ctx(30_000))
        assertEquals(AutoSwitchAction.SKIP, r.action)
        assertEquals(AutoSwitchReason.GATE_ABSENT, r.reason)
        assertEquals(30_000L, r.verdict?.darkForMs)
        lux = null
        assertEquals(AutoSwitchReason.GATE_UNKNOWN, p.evaluate(cand(31_000, "n3"), ctx(31_000)).reason)
    }

    @Test fun disabledNeverSwitchesAndSkipsGate() {
        val r = policy.evaluate(cand(0), ctx(0, enabled = false))
        assertEquals(AutoSwitchAction.SKIP, r.action)
        assertEquals(AutoSwitchReason.DISABLED, r.reason)
        assertEquals(0, gate.calls)
    }

    // --- AC18: low / 古い ---

    @Test fun lowPriorityNeverSwitchesEvenWhenPresent() {
        val r = policy.evaluate(cand(0, low = true), ctx(0))
        assertEquals(AutoSwitchAction.SKIP, r.action)
        assertEquals(AutoSwitchReason.LOW_PRIORITY, r.reason)
        assertEquals(0, gate.calls)
    }

    @Test fun tooOldSkipsAtEvaluation() {
        val r = policy.evaluate(cand(0, age = 25), ctx(5_001)) // 25 + 5.001 秒 > 30
        assertEquals(AutoSwitchAction.SKIP, r.action)
        assertEquals(AutoSwitchReason.TOO_OLD, r.reason)
        assertEquals(AutoSwitchAction.SWITCH, policy.evaluate(cand(0, age = 25), ctx(5_000)).action)
    }

    // --- すでに B にいる ---

    @Test fun alreadyOnBUpdatesCardOnly() {
        val r = policy.evaluate(cand(0), ctx(0, onB = true))
        assertEquals(AutoSwitchAction.CARD_ONLY, r.action)
        assertFalse(r.openMic)
        assertEquals(0, gate.calls)
    }

    // --- Defer と onSettled ---

    @Test fun busyDefersThenSettledReevaluatesExactlyOnce() {
        val r = policy.evaluate(cand(0, age = 2), ctx(0, busy = true))
        assertEquals(AutoSwitchAction.DEFER, r.action)
        assertEquals(0, gate.calls)
        assertTrue(policy.hasPending)

        val s = policy.onSettled(ctx(10_000))!!
        assertEquals(AutoSwitchAction.SWITCH, s.action)
        assertEquals(1, gate.calls)
        assertFalse(policy.hasPending)
        assertNull("一度だけ", policy.onSettled(ctx(11_000)))
        assertEquals(1, gate.calls)
    }

    @Test fun settledSkipsWhenCandidateGotTooOld() {
        policy.evaluate(cand(0, age = 5), ctx(0, busy = true))
        val s = policy.onSettled(ctx(26_000))!! // 5 + 26 秒 > 30
        assertEquals(AutoSwitchAction.SKIP, s.action)
        assertEquals(AutoSwitchReason.TOO_OLD, s.reason)
        assertNull(policy.onSettled(ctx(27_000)))
    }

    @Test fun settledAtExactAgeLimitStillSwitches() {
        policy.evaluate(cand(0, age = 5), ctx(0, busy = true))
        assertEquals(AutoSwitchAction.SWITCH, policy.onSettled(ctx(25_000))!!.action)
    }

    @Test fun settledOnBIsCardOnly() {
        policy.evaluate(cand(0), ctx(0, busy = true))
        val s = policy.onSettled(ctx(1_000, onB = true))!!
        assertEquals(AutoSwitchAction.CARD_ONLY, s.action)
        assertNull(policy.onSettled(ctx(2_000)))
    }

    @Test fun settledUsesGateAtThatTimeAndSkipsWhenAbsent() {
        policy.evaluate(cand(0), ctx(0, busy = true))
        gate.presence = Presence.ABSENT
        assertEquals(AutoSwitchReason.GATE_ABSENT, policy.onSettled(ctx(1_000))!!.reason)
    }

    @Test fun settledWhileStillBusyKeepsPending() {
        policy.evaluate(cand(0), ctx(0, busy = true))
        assertEquals(AutoSwitchAction.DEFER, policy.onSettled(ctx(1_000, busy = true))!!.action)
        assertTrue(policy.hasPending)
        assertEquals(AutoSwitchAction.SWITCH, policy.onSettled(ctx(2_000))!!.action)
    }

    @Test fun newerCandidateReplacesPendingAndCancelDropsIt() {
        policy.evaluate(cand(0, "old"), ctx(0, busy = true))
        policy.evaluate(cand(1_000, "newer"), ctx(1_000, busy = true))
        assertEquals(AutoSwitchAction.SWITCH, policy.onSettled(ctx(2_000))!!.action)
        assertEquals("1 件分しか評価しない", 1, gate.calls)

        policy.evaluate(cand(3_000, "x"), ctx(3_000, busy = true))
        policy.cancelPending() // onPause
        assertNull(policy.onSettled(ctx(4_000)))
    }

    @Test fun replacedPendingCandidateDecidesTheOutcomeAtSettle() {
        // 古い候補(age 25 秒)だけなら、10 秒後の確定では 35 秒で TOO_OLD になる。新しい候補(受信 5 秒、age 0)に置き換わっていれば有効
        policy.evaluate(cand(0, "old", age = 25), ctx(0, busy = true))
        policy.evaluate(cand(5_000, "newer", age = 0), ctx(5_000, busy = true))
        val s = policy.onSettled(ctx(10_000))!!
        assertEquals(AutoSwitchAction.SWITCH, s.action)
        assertEquals(AutoSwitchReason.PRESENT, s.reason)
        assertNull("置き換えられた古い候補は残らない", policy.onSettled(ctx(10_001)))

        // 逆順の確認: 新しい候補のほうが古くなっていれば、置き換え後の候補で TOO_OLD になる(古い候補の有効性に引きずられない)
        policy.evaluate(cand(0, "fresh", age = 0), ctx(0, busy = true))
        policy.evaluate(cand(1_000, "stale", age = 29), ctx(1_000, busy = true))
        val t = policy.onSettled(ctx(5_000))!! // stale: 29 + 4 秒 > 30
        assertEquals(AutoSwitchAction.SKIP, t.action)
        assertEquals(AutoSwitchReason.TOO_OLD, t.reason)
    }

    // --- DEFER → onSettled 経由でもマイクの歯止めは効く(AC19) ---

    /** 候補は `at` に届いて操作中で見送られ、5 秒後に確定する(候補の有効期限 30 秒の内側) */
    private fun deferThenSettle(at: Long, listen: Boolean = true): AutoSwitchResult {
        policy.evaluate(cand(at, "d$at"), ctx(at, listen = listen, busy = true))
        return policy.onSettled(ctx(at + 5_000, listen = listen))!!
    }

    private fun assertSwitchWithoutMic(r: AutoSwitchResult, denial: MicDenial) {
        assertEquals(AutoSwitchAction.SWITCH, r.action)
        assertFalse(r.openMic)
        assertEquals(denial, r.micDenial)
    }

    @Test fun micBrakesApplyAfterDeferTheSameAsDirectEvaluation() {
        // 歯止めなし: 開く
        val first = deferThenSettle(0)
        assertEquals(AutoSwitchAction.SWITCH, first.action)
        assertTrue(first.openMic)
        // auto_listen オフ
        assertSwitchWithoutMic(deferThenSettle(2_000, listen = false), MicDenial.AUTO_LISTEN_OFF)
        // クールダウン(5 分)
        policy.onAutoMicStarted(10_000)
        assertSwitchWithoutMic(deferThenSettle(20_000), MicDenial.COOLDOWN)
        assertTrue("5 分を過ぎれば開く", deferThenSettle(20_000 + 6 * minute).openMic)
    }

    @Test fun hourlyLimitAppliesAfterDefer() {
        for (i in 0 until 6) policy.onAutoMicStarted(i * 5 * minute) // 0〜25 分
        assertSwitchWithoutMic(deferThenSettle(30 * minute), MicDenial.HOURLY_LIMIT)
        assertTrue("最古が 1 時間前になれば 1 回分空く", deferThenSettle(60 * minute - 5_000).openMic)
    }

    @Test fun noSpeechHoldAppliesAfterDefer() {
        policy.onAutoMicStarted(0)
        policy.onSessionEnded(autoStarted = true, reason = "no_speech")
        assertSwitchWithoutMic(deferThenSettle(10 * minute), MicDenial.AFTER_NO_SPEECH)
        assertSwitchWithoutMic(deferThenSettle(100 * minute), MicDenial.AFTER_NO_SPEECH)
    }

    @Test fun settledWhenDisabledMeanwhileSkips() {
        policy.evaluate(cand(0), ctx(0, busy = true))
        val s = policy.onSettled(ctx(1_000, enabled = false))!!
        assertEquals(AutoSwitchReason.DISABLED, s.reason)
    }

    // --- AC19: 自動マイク起動の歯止め ---

    private fun micAt(now: Long, id: String = "n$now", listen: Boolean = true): AutoSwitchResult =
        policy.evaluate(cand(now, id), ctx(now, listen = listen))

    @Test fun autoListenFalseSwitchesButNeverOpensMic() {
        val r = micAt(0, listen = false)
        assertEquals(AutoSwitchAction.SWITCH, r.action)
        assertFalse(r.openMic)
        assertEquals(MicDenial.AUTO_LISTEN_OFF, r.micDenial)
    }

    @Test fun firstAutoMicOpens() {
        val r = micAt(0)
        assertTrue(r.openMic)
        assertNull(r.micDenial)
    }

    @Test fun cooldownFiveMinutes() {
        policy.onAutoMicStarted(0)
        val early = micAt(5 * minute - 1)
        assertEquals("切り替えはできる", AutoSwitchAction.SWITCH, early.action)
        assertFalse(early.openMic)
        assertEquals(MicDenial.COOLDOWN, early.micDenial)
        assertTrue("5 分ちょうどで解除", micAt(5 * minute).openMic)
    }

    @Test fun evaluateAloneDoesNotCountAsStart() {
        assertTrue(micAt(0).openMic)
        assertTrue("onAutoMicStarted を呼ぶまで数えない", micAt(1_000, "n2").openMic)
    }

    @Test fun sixPerHourSlidingWindow() {
        for (i in 0 until 6) {
            val t = i * 6 * minute // 0, 6, 12, 18, 24, 30 分
            assertTrue("$i", micAt(t).openMic)
            policy.onAutoMicStarted(t)
        }
        val seventh = micAt(36 * minute)
        assertEquals(AutoSwitchAction.SWITCH, seventh.action)
        assertFalse(seventh.openMic)
        assertEquals(MicDenial.HOURLY_LIMIT, seventh.micDenial)
        // 最古(0 分)が 1 時間前になる 60 分ちょうどで 1 回分空く
        assertEquals(MicDenial.HOURLY_LIMIT, micAt(60 * minute - 1).micDenial)
        assertTrue(micAt(60 * minute).openMic)
        policy.onAutoMicStarted(60 * minute)
        // その直後はクールダウン、その次の空きは 66 分
        assertEquals(MicDenial.COOLDOWN, micAt(61 * minute).micDenial)
        assertEquals(MicDenial.HOURLY_LIMIT, micAt(65 * minute).micDenial)
        assertTrue(micAt(66 * minute).openMic)
    }

    @Test fun noSpeechAfterAutoStartHoldsNextAutoMic() {
        policy.onAutoMicStarted(0)
        policy.onSessionEnded(autoStarted = true, reason = "no_speech")
        val r = micAt(10 * minute)
        assertEquals("切替は止めない", AutoSwitchAction.SWITCH, r.action)
        assertFalse(r.openMic)
        assertEquals(MicDenial.AFTER_NO_SPEECH, r.micDenial)
        assertEquals("時間では解除しない", MicDenial.AFTER_NO_SPEECH, micAt(10 * 60 * minute).micDenial)
    }

    @Test fun holdReleasedByManualSessionEndingWithSpeech() {
        policy.onSessionEnded(true, "no_speech")
        policy.onSessionEnded(autoStarted = false, reason = "no_speech") // 手動でも無発話なら解除しない
        assertEquals(MicDenial.AFTER_NO_SPEECH, micAt(0).micDenial)
        policy.onSessionEnded(autoStarted = false, reason = "ended")
        assertTrue(micAt(1_000, "n2").openMic)
    }

    @Test fun autoSessionEndingNormallyClearsHold() {
        policy.onSessionEnded(true, "no_speech")
        policy.onSessionEnded(true, "ended")
        assertTrue(micAt(0).openMic)
        policy.onSessionEnded(true, null)
        assertTrue(micAt(1_000, "n2").openMic)
    }

    @Test fun nonNoSpeechEndsDoNotHold() {
        policy.onSessionEnded(true, "timeout")
        assertTrue(micAt(0).openMic)
    }

    @Test fun gateBlocksMicDecisionsToo() {
        gate.presence = Presence.ABSENT
        val r = micAt(0)
        assertFalse(r.openMic)
        assertNull("切り替えないときは歯止めの理由も出さない", r.micDenial)
    }

    @Test fun lastResultKeepsDiagnostics() {
        assertNull(policy.lastResult)
        policy.evaluate(cand(0), ctx(0))
        assertEquals(AutoSwitchReason.PRESENT, policy.lastResult?.reason)
        assertNotNull(policy.lastResult?.verdict)
        gate.presence = Presence.ABSENT
        policy.evaluate(cand(0, "n2"), ctx(0))
        assertEquals(AutoSwitchReason.GATE_ABSENT, policy.lastResult?.reason)
    }

    @Test fun trackerToPolicyEndToEnd() {
        val tracker = NoticeTracker()
        fun info(rev: Long, vararg ns: DaidaiNotice) = DaidaiInfo(rev, ns.toList(), null, false, null)
        assertNull(tracker.onStatus(info(1, notice("a")), 0))
        val c = tracker.onStatus(info(2, notice("b", age = 1), notice("a")), 100)!!
        assertEquals(AutoSwitchAction.SWITCH, policy.evaluate(c, ctx(100)).action)
        assertNull("SSE とポーリングの二重", tracker.onStatus(info(2, notice("b", age = 2), notice("a")), 1_100))
    }
}
