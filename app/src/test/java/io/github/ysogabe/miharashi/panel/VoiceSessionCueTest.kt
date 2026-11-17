package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 開始の合図の段(段 G。設計書(段階 2)§5.8.4・§5.8.6)。作成 → マイクを閉じたまま挨拶 → 300 ms → マイク → 最初の 200 ms を捨てる。 */
class VoiceSessionCueTest {
    private fun wav(ms: Int, rate: Int = 16_000) = StartChime.wrap(ShortArray(rate * ms / 1000) { 1000 }, rate)

    private val greeting = wav(2_000)
    private val chime = StartChime.wav()
    private val choice = CueChoice("g_day_swipe", 42L)

    private fun plan(g: ByteArray? = greeting, c: ByteArray = chime, ch: CueChoice? = choice) = StartCuePlan(g, c, ch)

    private fun turn(plan: StartCuePlan?, auto: Boolean = false, follow: Boolean = false) =
        TurnRequest(StartReason.SWIPE, null, auto, conversation = ConversationParam(null), follow = follow, tag = TurnTag(1, 1), cue = plan)

    /** マイクを開いてから `ms` 話したとき、送った PCM のバイト数(キューの残りは 20 ms 未満) */
    private fun Rig.sentBytesAfterTalking(ms: Int): Int {
        talk(ms)
        return bridge.chunks.sumOf { it.pcm.size }
    }

    @Test fun greetingPlaysWithTheMicClosedAndTheMicOpensOnlyAfterTheEchoWait() {
        val r = Rig()
        r.startTurn(turn(plan()))
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals(1, r.player.plays)
        assertSame(greeting, r.player.lastWav)
        assertEquals("挨拶の間はマイクを開かない", 0, r.audio.startCount)
        assertTrue("挨拶の間はアバターを talking に", r.last.cuePlaying)
        assertFalse("マイク使用中はまだ出さない", r.last.micActive)
        assertTrue(r.last.active)
        assertEquals(listOf<CueChoice?>(choice), r.listener.cueStarts)
        assertTrue("作成 → SSE → 挨拶の順", r.log.indexOf("create") < r.log.indexOf("events") && r.log.indexOf("events") < r.log.indexOf("player.play"))

        r.player.finish(true)
        r.sim.settle()
        assertFalse(r.last.cuePlaying)
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS - 1)
        assertEquals("完了 + 300 ms より前は開かない", 0, r.audio.startCount)
        r.advance(1)
        assertEquals(1, r.audio.startCount)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(listOf(MicCue.HEARD), r.listener.micCues)
        assertEquals(1, r.listener.micOpenedAt.size)
        assertEquals("挨拶は 1 回だけ", 1, r.player.plays)
    }

    @Test fun afterTheGreetingOnlyTheFirst200msAreDiscarded() {
        val r = Rig()
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        val sent = r.sentBytesAfterTalking(1_000)
        val bytesPerMs = VoiceLimits.CHUNK_MIN_BYTES / 20
        // 捨てるのは 200 ms。送ったのは 800 ms から、キューに残る 20 ms 未満を引いた分
        assertTrue("sent=$sent", sent in (780 * bytesPerMs)..(800 * bytesPerMs))
    }

    @Test fun withoutACueTheBeepPathKeepsTheLongGuard() {
        val r = Rig()
        r.startTurn(turn(null))
        assertEquals(0, r.player.plays)
        assertEquals(listOf(MicCue.NONE), r.listener.micCues)
        assertTrue(r.listener.cueStarts.isEmpty())
        val sent = r.sentBytesAfterTalking(1_000)
        val bytesPerMs = VoiceLimits.CHUNK_MIN_BYTES / 20
        assertTrue("400 ms を捨てる: sent=$sent", sent in (580 * bytesPerMs)..(600 * bytesPerMs))
    }

    @Test fun followTurnsIgnoreTheCueAndKeepTheirOwnTone() {
        val r = Rig()
        r.startTurn(turn(plan(), follow = true))
        assertEquals("続きの往復には挨拶を付けない", 0, r.player.plays)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(listOf(MicCue.NONE), r.listener.micCues)
        assertTrue(r.listener.micOpenedFollow.single())
    }

    @Test fun aFailedGreetingFallsBackToTheChimeInTheSameOrder() {
        val r = Rig()
        r.startTurn(turn(plan()))
        r.player.finish(false)
        r.sim.settle()
        assertEquals(2, r.player.plays)
        assertArrayEquals(chime, r.player.lastWav)
        assertEquals(0, r.audio.startCount)
        assertTrue(r.last.cuePlaying)
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS - 1)
        assertEquals(0, r.audio.startCount)
        r.advance(1)
        assertEquals(listOf(MicCue.HEARD), r.listener.micCues)
        assertEquals("合図の開始の通知は 1 回だけ(挨拶のとき)", listOf<CueChoice?>(choice), r.listener.cueStarts)
    }

    @Test fun aGreetingThatOverrunsIsStoppedAndItsLateCompletionIsIgnored() {
        val r = Rig()
        r.startTurn(turn(plan()))
        r.advance(2_000 + VoiceLimits.CUE_PLAYBACK_MARGIN_MS - 1)
        assertEquals(1, r.player.plays)
        r.advance(1)
        assertEquals("期限(長さ + 1.5 秒)でチャイムへ", 2, r.player.plays)
        assertTrue(r.player.stopCount >= 1)
        // 止めた挨拶の完了が遅れて届いても、チャイムの完了と取り違えない
        r.player.dones[0](true)
        r.sim.settle()
        r.advance(1_000)
        assertEquals(0, r.audio.startCount)
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals(1, r.audio.startCount)
        assertEquals(listOf(MicCue.HEARD), r.listener.micCues)
    }

    @Test fun whenNeitherGreetingNorChimeSoundsTheMicOpensWithTheBeepAndTheLongGuard() {
        val r = Rig()
        r.startTurn(turn(plan(g = null)))
        assertEquals("挨拶が無ければチャイムから", listOf<CueChoice?>(null), r.listener.cueStarts)
        assertArrayEquals(chime, r.player.lastWav)
        r.player.finish(false)
        r.sim.settle()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals("合図が 0 回の経路を作らない: 開始音へ", listOf(MicCue.FAILED), r.listener.micCues)
        val sent = r.sentBytesAfterTalking(1_000)
        val bytesPerMs = VoiceLimits.CHUNK_MIN_BYTES / 20
        assertTrue("sent=$sent", sent in (580 * bytesPerMs)..(600 * bytesPerMs))
    }

    @Test fun aPlayerThatThrowsFallsThroughToTheBeep() {
        val r = Rig()
        r.player.throwOnPlay = IllegalStateException("no audio")
        r.startTurn(turn(plan()))
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(listOf(MicCue.FAILED), r.listener.micCues)
    }

    @Test fun anInvalidOrTooLongGreetingIsNotPlayedAndTheChimeIsUsed() {
        for (bad in listOf(wav(5_001), ByteArray(100), wav(5_001, 48_000))) {
            val r = Rig()
            r.startTurn(turn(plan(g = bad)))
            assertEquals(listOf<CueChoice?>(null), r.listener.cueStarts)
            assertEquals(1, r.player.plays)
            assertArrayEquals(chime, r.player.lastWav)
        }
        val r = Rig()
        r.startTurn(turn(plan(g = wav(5_000))))
        assertEquals("ちょうど 5 秒は使う", listOf<CueChoice?>(choice), r.listener.cueStarts)
    }

    @Test fun cancellingDuringTheGreetingStopsItAndNeverRecords() {
        val r = Rig()
        r.startTurn(turn(plan(), auto = true))
        assertTrue(r.player.playing)
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertTrue(r.player.stopCount >= 1)
        assertEquals(1, r.bridge.cancels.size)
        assertEquals(TurnOutcome.CANCELLED, r.listener.outcomes.single())
        r.player.dones[0](true) // 止めたあとに届いた完了
        r.sim.settle()
        r.advance(10_000)
        assertEquals("録音は 0 回", 0, r.audio.startCount)
        assertTrue(r.listener.micCues.isEmpty())
        assertFalse(r.last.cuePlaying)
    }

    @Test fun cancellingDuringTheEchoWaitNeverOpensTheMic() {
        val r = Rig()
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        r.advance(100)
        r.cancel()
        r.advance(1_000)
        assertEquals(0, r.audio.startCount)
        assertEquals(1, r.bridge.cancels.size)
    }

    @Test fun aStopMicNowBeforeTheCueEndsPreventsTheMic() {
        val r = Rig()
        r.startTurn(turn(plan()))
        r.session.stopMicNow() // 許可を同期に取り消す(取消の投稿はまだ)
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    @Test fun aCancelWhileTheCreateResultIsInFlightNeverGreets() {
        val r = Rig()
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits), latencyMs = 100) }
        r.startTurn(turn(plan()))
        assertEquals(1, r.bridge.created.size)
        r.session.stopMicNow() // 許可だけを同期に取り消す(作成の応答はまだ届いていない)
        r.advance(100)
        assertTrue("挨拶を始めない", r.listener.cueStarts.isEmpty())
        assertEquals(0, r.player.plays)
        assertEquals(0, r.audio.startCount)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    @Test fun aChimeThatOverrunsFallsThroughToTheBeep() {
        val r = Rig()
        r.startTurn(turn(plan(g = null)))
        r.advance(StartChime.DURATION_MS + VoiceLimits.CUE_PLAYBACK_MARGIN_MS - 1)
        assertEquals(0, r.audio.startCount)
        r.advance(1)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(listOf(MicCue.FAILED), r.listener.micCues)
    }

    @Test fun theGreetingIsLookedUpAfterTheCreateReturns() {
        val r = Rig()
        var available: ByteArray? = null
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits), latencyMs = 100) }
        r.startTurn(turn(StartCuePlan({ available }, chime, choice)))
        available = greeting // 入場の直前に頼んだ取り直しが、作成の往復のあいだに終わった
        r.advance(100)
        assertSame(greeting, r.player.lastWav)
        assertEquals(listOf<CueChoice?>(choice), r.listener.cueStarts)
    }

    @Test fun aFailedCreateNeverGreets() {
        val r = Rig()
        r.bridge.createReply = { failWith(bridgeError(BridgeFailure.SERVER_ERROR, 503)) }
        r.startTurn(turn(plan()))
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals("作成が失敗したら挨拶しない", 0, r.player.plays)
        assertTrue(r.listener.cueStarts.isEmpty())
    }

    @Test fun theSessionTimeLimitStillAppliesDuringTheCue() {
        val r = Rig(sessionMaxMs = 1_000)
        r.startTurn(turn(plan()))
        r.advance(1_000)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.TIMEOUT, r.last.error)
        assertEquals(0, r.audio.startCount)
        assertNull(r.player.lastWav?.takeIf { r.player.playing })
    }
}
