package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段階 2b-2(R15): 外へ出る脳(host 以外)のとき、画面が表記を描き終えた確認が届くまで、`openMic` の関所が録音を始めないこと。
 * 確認が来ない画面は [Rig.screenDraws] = false で再現する(確認は [Rig.drawNotice] で明示して流す)。
 */
class VoiceSessionBrainGateTest {
    private val cloud = BrainInfo(BrainName.DAIDAI, BrainEgress.CLOUD)
    private val lan = BrainInfo(BrainName.OTHER, BrainEgress.LAN)
    private val unknown = BrainInfo(BrainName.OTHER, BrainEgress.UNKNOWN)
    private val host = BrainInfo(BrainName.OTHER, BrainEgress.HOST)

    private fun Rig.brainIs(vararg perCreate: BrainInfo?, handle: String? = null) {
        bridge.createReply = { n ->
            ok(SessionInfo("s-$n", bridge.hostLimits, conversation = handle?.let { ConversationInfo("$it-$n", null, true) }, brain = perCreate.getOrNull(n - 1)))
        }
    }

    private fun turn(plan: StartCuePlan? = null, follow: Boolean = false) =
        TurnRequest(StartReason.SWIPE, null, false, conversation = ConversationParam(null), follow = follow, tag = TurnTag(1, 1), cue = plan)

    private fun plan() = StartCuePlan(StartChime.wrap(ShortArray(16_000) { 1000 }, 16_000), StartChime.wav(), null)

    private fun held(brain: BrainInfo? = cloud): Rig = Rig().also { it.screenDraws = false; it.brainIs(brain, brain, brain) }

    // ---- 確認が来ないあいだは、録音も送信もしない

    @Test fun withoutTheConfirmationTheMicIsNeverOpenedAndNothingIsSent() {
        val r = held()
        r.startTurn(turn())
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals("確認が来るまで audio.start を呼ばない", 0, r.audio.startCount)
        assertFalse(r.audio.recording)
        // 期限の直前まで進め、音を渡そうとしても、何も送られない
        r.talk((VoiceLimits.BRAIN_NOTICE_DRAW_MS - 20).toInt())
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals(0, r.audio.startCount)
        assertEquals("チャンクの送信 0 回", 0, r.bridge.chunks.size)
        assertTrue(r.listener.micOpenedAt.isEmpty())
        assertTrue(r.listener.snaps.none { it.micActive })
    }

    @Test fun theBrainSnapshotCarriesTheTokenThatTheScreenReturns() {
        val r = held()
        r.startTurn(turn())
        val s = r.listener.snaps.first { it.brain != null }
        assertNotEquals("外へ出る脳には確認の印がある", 0, s.brainAck)
        assertTrue("印のあるスナップショットは、表記の条件を満たす", s.drawnBrainAck() == s.brainAck)
        assertTrue("作成の前のスナップショットには印がない", r.listener.snaps.takeWhile { it.brain == null }.all { it.brainAck == 0 })
    }

    @Test fun theConfirmationOpensTheMicAndTheDeadlineIsCancelled() {
        val r = held()
        r.startTurn(turn())
        r.drawNotice()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
        r.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS * 2)
        assertEquals("期限のタイマーは確認で消える", VoicePhase.LISTENING, r.phase)
        assertNull(r.last.error)
        // 重複して届いた確認は、何もしない
        r.drawNotice()
        assertEquals(1, r.audio.startCount)
    }

    @Test fun everyEgressOtherThanHostWaitsForTheConfirmation() {
        for (b in listOf(cloud, lan, unknown)) {
            val r = held(b)
            r.startTurn(turn())
            assertEquals("$b", VoicePhase.PREPARING, r.phase)
            assertEquals("$b", 0, r.audio.startCount)
            r.drawNotice()
            assertEquals("$b", VoicePhase.LISTENING, r.phase)
        }
    }

    // ---- 期限切れは、録音せずに閉じる

    @Test fun theDeadlineEndsWithMicUnavailableWithoutRecordingAndDeletesOnce() {
        val r = held()
        r.brainIs(cloud, handle = "h")
        r.startTurn(turn())
        r.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS - 1)
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.last.error)
        assertEquals("mic: brain notice not drawn", r.last.diagnostics)
        assertEquals("録音を始めない", 0, r.audio.startCount)
        assertEquals("ホストのセッションを DELETE", listOf("s-1"), r.bridge.cancels.toList())
        assertEquals(listOf(Pair(false, "mic_unavailable")), r.listener.ended.toList())
        assertEquals(0, r.bridge.chunks.size)
        assertTrue("マイク使用中のスナップショットは出ていない", r.listener.snaps.none { it.micActive })
        // 遅れて届いた確認では、終わったセッションのマイクは開かない
        r.drawNotice(r.listener.snaps.first { it.brainAck != 0 }.brainAck)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.ERROR, r.phase)
    }

    // ---- 待ちの間の取消

    @Test fun cancellingWhileWaitingEndsWithoutRecordingAndTheDeadlineDoesNothingLater() {
        val r = held()
        r.startTurn(turn())
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals("録音 0 回", 0, r.audio.startCount)
        assertEquals(listOf("s-1"), r.bridge.cancels.toList())
        assertEquals(listOf(Pair(false, "cancelled")), r.listener.ended.toList())
        r.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS * 2)
        assertEquals("期限が過ぎても、取消のあとにエラーを出さない", VoicePhase.IDLE, r.phase)
        assertEquals(1, r.listener.ended.size)
        assertEquals(1, r.bridge.cancels.size)
        // 取消のあとに届いた確認も、マイクを開かない
        r.drawNotice(r.listener.snaps.first { it.brainAck != 0 }.brainAck)
        assertEquals(0, r.audio.startCount)
    }

    @Test fun stopMicNowWhileWaitingKeepsTheMicClosed() {
        val r = held()
        r.startTurn(turn())
        r.session.stopMicNow()
        r.drawNotice()
        assertEquals("許可が取り消されたあとは、確認が来ても録音しない", 0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    // ---- 古い往復の確認

    @Test fun aConfirmationOfAnOlderTurnNeverOpensTheMicOfTheNewOne() {
        val r = held()
        r.startTurn(turn())
        val t1 = r.listener.snaps.last { it.brainAck != 0 }.brainAck
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)

        r.startTurn(turn())
        val t2 = r.listener.snaps.last { it.brainAck != 0 }.brainAck
        assertNotEquals("往復ごとに新しい印", t1, t2)
        assertEquals(VoicePhase.PREPARING, r.phase)

        r.drawNotice(t1)
        assertEquals("古い往復の確認では開かない", VoicePhase.PREPARING, r.phase)
        assertEquals(0, r.audio.startCount)
        r.drawNotice(0)
        assertEquals("0(不要の印)も受けない", 0, r.audio.startCount)
        r.drawNotice(t2)
        assertEquals("新しい往復の確認だけが通す", VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
    }

    @Test fun aSecondSessionNeverReusesTheTokenOfAnEarlierSession() {
        val a = held()
        a.startTurn(turn())
        val b = held()
        b.startTurn(turn())
        assertNotEquals("別のセッション(runtime の作り直し)でも印が重ならない", a.listener.snaps.last { it.brainAck != 0 }.brainAck, b.listener.snaps.last { it.brainAck != 0 }.brainAck)
    }

    // ---- host と旧ホストは待たない

    @Test fun hostBrainAndOldHostOpenTheMicWithoutAnyConfirmation() {
        for (b in listOf(host, null)) {
            val r = held(b)
            r.startTurn(turn())
            assertEquals("$b", VoicePhase.LISTENING, r.phase)
            assertEquals("$b", 1, r.audio.startCount)
            assertTrue("$b: 印を載せない", r.listener.snaps.all { it.brainAck == 0 })
            r.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS * 2)
            assertEquals("$b: 待ちの期限は無い", VoicePhase.LISTENING, r.phase)
        }
    }

    @Test fun followTurnWithoutACueAlsoWaitsAtTheSameGate() {
        val r = held()
        r.startTurn(turn(follow = true))
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals(0, r.audio.startCount)
        r.drawNotice()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
    }

    // ---- 合図との関係

    @Test fun aConfirmationDuringTheCueIsRememberedAndDoesNotOpenTheMicMidGreeting() {
        val r = held()
        r.startTurn(turn(plan()))
        assertTrue(r.last.cuePlaying)
        r.drawNotice()
        assertEquals("合図の最中は開かない", 0, r.audio.startCount)
        assertTrue(r.last.cuePlaying)
        r.player.finish(true)
        r.sim.settle()
        assertEquals("残響の待ちの間も開かない", 0, r.audio.startCount)
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS - 1)
        assertEquals(0, r.audio.startCount)
        r.advance(1)
        assertEquals("確認が済んでいれば、関所では待たずに開く", VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
        assertEquals(MicCue.HEARD, r.listener.micCues.single())
    }

    @Test fun withoutAConfirmationTheGateHoldsAfterTheCueAndTheDeadlineStartsThere() {
        val r = held()
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals("合図が済んでも確認が無ければ関所で待つ", VoicePhase.PREPARING, r.phase)
        assertEquals(0, r.audio.startCount)
        r.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS - 1)
        assertEquals(VoicePhase.PREPARING, r.phase)
        r.advance(1)
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.last.error)
        assertEquals(0, r.audio.startCount)
    }

    @Test fun aConfirmationWhileTheGateHoldsAfterTheCueOpensTheMic() {
        val r = held()
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals(0, r.audio.startCount)
        r.drawNotice()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(MicCue.HEARD, r.listener.micCues.single())
    }

    @Test fun whenNeitherCueCanPlayTheGateStillHoldsBeforeTheStartTone() {
        val r = held()
        r.player.throwOnPlay = IllegalStateException("no audio")
        r.startTurn(turn(plan()))
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals(0, r.audio.startCount)
        assertTrue("開始音は録音の開始の後ろ", r.listener.micOpenedAt.isEmpty())
        r.drawNotice()
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(MicCue.FAILED, r.listener.micCues.single())
    }

    @Test fun cancellingDuringTheCueNeverReachesTheGate() {
        val r = held()
        r.startTurn(turn(plan()))
        r.cancel()
        r.drawNotice(r.listener.snaps.last { it.brainAck != 0 }.brainAck)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
    }
}
