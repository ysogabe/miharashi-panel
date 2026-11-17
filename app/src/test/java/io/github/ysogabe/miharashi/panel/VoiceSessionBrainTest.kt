package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 段階 2b-2: 201 の `brain` が、マイクを開く・合図を鳴らすより前のスナップショットから載り、往復ごとに決まる(持ち越さない)こと。
 * 「マイクが開いているのに、外へ出る脳の表記が無い」スナップショットが 1 つも出ないことを固定する(R15)。
 */
class VoiceSessionBrainTest {
    private val cloudDaidai = BrainInfo(BrainName.DAIDAI, BrainEgress.CLOUD)
    private val host = BrainInfo(BrainName.OTHER, BrainEgress.HOST)

    private fun Rig.brainIs(vararg perCreate: BrainInfo?) {
        bridge.createReply = { n -> ok(SessionInfo("s-$n", bridge.hostLimits, brain = perCreate.getOrNull(n - 1))) }
    }

    private fun turn(plan: StartCuePlan? = null, follow: Boolean = false) =
        TurnRequest(StartReason.SWIPE, null, false, conversation = ConversationParam(null), follow = follow, tag = TurnTag(1, 1), cue = plan)

    private fun plan() = StartCuePlan(StartChime.wrap(ShortArray(16_000) { 1000 }, 16_000), StartChime.wav(), null)

    /** 表記が要るのに無いスナップショット: マイクが開いている(または合図の再生中)のに、外へ出る側の脳が載っていない */
    private fun Rig.assertNoMicOrCueSnapshotWithoutTheBrain(expected: BrainInfo?) {
        val risky = listener.snaps.filter { it.micActive || it.cuePlaying }
        assertTrue("マイクか合図の再生中のスナップショットがある", risky.isNotEmpty())
        for (s in risky) assertEquals("マイク・合図の間は必ず脳が載る: $s", expected, s.brain)
    }

    @Test fun theBrainIsInEverySnapshotWhileTheMicIsOpenWithoutAGreeting() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn())
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.assertNoMicOrCueSnapshotWithoutTheBrain(cloudDaidai)
        r.talk(440)
        r.speech()
        r.transcript("こんにちは")
        r.assertNoMicOrCueSnapshotWithoutTheBrain(cloudDaidai)
        assertTrue("認識中の LISTENING / HEARING のどちらも", r.listener.snaps.any { it.state == VoiceState.LISTENING && it.micActive })
    }

    @Test fun theBrainIsInTheSnapshotsOfTheGreetingAndOfTheMicThatFollows() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn(plan()))
        assertTrue(r.last.cuePlaying)
        assertEquals("挨拶の再生中のスナップショットに脳が載る", cloudDaidai, r.last.brain)
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.assertNoMicOrCueSnapshotWithoutTheBrain(cloudDaidai)
        assertTrue(r.listener.snaps.any { it.cuePlaying })
    }

    @Test fun theBrainIsHeldBeforeTheCueAndTheMicEvenWhenTheGreetingFailsToPlay() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.player.throwOnPlay = IllegalStateException("no audio") // 挨拶もチャイムも鳴らせない: 作成の直後にマイクを開く
        r.startTurn(turn(plan()))
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.assertNoMicOrCueSnapshotWithoutTheBrain(cloudDaidai)
    }

    /** 画面側の条件([DaidaiUiState.brainNotice])を、スナップショットから [DaidaiVoice] と同じ写しで組んで確かめる */
    private fun VoiceSnapshot.noticeShown() = DaidaiUiState(
        voice = state, micActive = micActive, cuePlaying = cuePlaying, brain = brain, preparing = preparing,
    ).brainNotice != null

    /**
     * 通知の順序(1 往復ぶん)。201 のあとの最初の「脳つき」スナップショットは、マイク使用中のどのスナップショットよりも前で、
     * 表記の条件を満たす(スナップショットとして先に出る。実際に描かれたことは、関所が画面の確認で確かめる。[VoiceSessionBrainGateTest])。そこから録音が終わる(応答待ち)まで、表記が途切れない。
     */
    private fun assertNoticeComesFirstAndNeverBreaks(snaps: List<VoiceSnapshot>) {
        val firstBrain = snaps.indexOfFirst { it.brain != null }
        val firstMic = snaps.indexOfFirst { it.micActive }
        assertTrue("脳つきのスナップショットがある", firstBrain >= 0)
        assertTrue("マイク使用中のスナップショットがある", firstMic >= 0)
        assertTrue("脳つきのスナップショット($firstBrain)が、マイク使用中のどれ($firstMic)よりも先", firstBrain < firstMic)
        assertTrue("そのスナップショットが、表記の条件を満たす: ${snaps[firstBrain]}", snaps[firstBrain].noticeShown())
        assertFalse("最初の脳つきは、まだ録音も合図もしていない準備中", snaps[firstBrain].micActive || snaps[firstBrain].cuePlaying)
        assertTrue(snaps[firstBrain].preparing)
        val end = snaps.indexOfFirst { it.state == VoiceState.WAITING }.let { if (it < 0) snaps.size else it }
        for (i in firstBrain until end) assertTrue("録音が終わるまで表記が途切れない(#$i): ${snaps[i]}", snaps[i].noticeShown())
    }

    @Test fun theNoticeReachesTheScreenBeforeAnyMicSnapshotAfterACueTurn() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn(plan()))
        assertTrue(r.last.cuePlaying)
        r.player.finish(true)
        r.sim.settle()
        val gap = r.listener.snaps.size
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.talk(440)
        r.speech()
        r.transcript("こんにちは")
        assertNoticeComesFirstAndNeverBreaks(r.listener.snaps)
        // 合図の終わり(cuePlaying=false)から録音の開始までの間のスナップショットにも表記が残る
        val afterCue = r.listener.snaps.indexOfLast { it.preparing && !it.cuePlaying && it.brain != null && it.state == VoiceState.IDLE }
        assertTrue("合図が終わって録音が始まる前のスナップショットがある", afterCue in 0 until gap + 1)
        assertTrue(r.listener.snaps[afterCue].noticeShown())
    }

    @Test fun theNoticeReachesTheScreenBeforeAnyMicSnapshotInAFollowUpTurnWithoutACue() {
        val r = Rig()
        r.brainIs(cloudDaidai, host)
        r.startTurn(turn())
        r.talk(440)
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        val before = r.listener.snaps.size
        r.startTurn(turn(follow = true))
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.talk(440)
        val second = r.listener.snaps.drop(before)
        assertNoticeComesFirstAndNeverBreaks(second)
        assertEquals("2 往復目の脳", host, second.first { it.brain != null }.brain)
    }

    @Test fun theNoticeStaysThroughTheEchoWaitWhenTheCueWasHeard() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        // 残響の待ちの間(マイクはまだ開いていない)も、表記の条件を満たす
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertFalse(r.last.micActive)
        assertFalse(r.last.cuePlaying)
        assertTrue("残響の待ちの間: ${r.last}", r.last.noticeShown())
    }

    @Test fun anOldHostGetsNoNoticeInAnySnapshot() {
        val r = Rig()
        r.brainIs(null)
        r.startTurn(turn(plan()))
        r.player.finish(true)
        r.sim.settle()
        r.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        r.talk(440)
        assertTrue(r.listener.snaps.none { it.noticeShown() })
    }

    @Test fun snapshotsBeforeTheCreateResponseCarryNoBrain() {
        val r = Rig()
        r.bridge.createReply = { n -> ok(SessionInfo("s-$n", r.bridge.hostLimits, brain = cloudDaidai), latencyMs = 100) }
        r.session.start(turn())
        r.sim.settle()
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertNull("作成の応答の前は null", r.last.brain)
        r.advance(100)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(cloudDaidai, r.last.brain)
    }

    @Test fun anOldHostWithoutABrainLeavesItNullEverywhere() {
        val r = Rig()
        r.brainIs(null)
        r.startTurn(turn())
        r.talk(440)
        assertTrue(r.listener.snaps.isNotEmpty())
        assertTrue(r.listener.snaps.all { it.brain == null })
    }

    @Test fun theBrainIsDecidedPerTurnAndNeverCarriedOver() {
        val r = Rig()
        r.brainIs(cloudDaidai, host, null)
        // 1 往復目: クラウドの脳。返答まで進めて終える
        r.startTurn(turn())
        r.talk(440)
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        assertEquals(VoicePhase.DONE, r.phase)
        assertEquals("終わったあとのスナップショットも、この往復の脳", cloudDaidai, r.last.brain)

        // 2 往復目(続き): 新しいセッションの作成の前に、前の値を消す。201 でこの往復の値
        val before = r.listener.snaps.size
        r.startTurn(turn(follow = true))
        assertEquals(VoicePhase.LISTENING, r.phase)
        val second = r.listener.snaps.drop(before)
        assertNull("作成の前の最初のスナップショットは null(前の往復の値を持ち越さない)", second.first().brain)
        assertEquals(host, r.last.brain)
        assertTrue("2 往復目のマイクは host の脳", second.filter { it.micActive }.all { it.brain == host })
        assertTrue("2 往復目に、前のクラウドの脳が出ない", second.none { it.brain == cloudDaidai })
        r.talk(440)
        r.reply()
        r.player.finish(true)
        r.sim.settle()

        // 3 往復目: 旧ホスト(brain なし)。前の host の値が残らない
        val before3 = r.listener.snaps.size
        r.startTurn(turn(follow = true))
        assertTrue(r.listener.snaps.drop(before3).all { it.brain == null })
    }

    @Test fun aCreateFailureAfterACloudTurnShowsNoBrain() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn())
        r.talk(440)
        r.reply()
        r.player.finish(true)
        r.sim.settle()
        r.bridge.createReply = { failWith(IOException("down")) }
        val before = r.listener.snaps.size
        r.startTurn(turn(follow = true))
        assertEquals(VoicePhase.ERROR, r.phase)
        assertTrue("作成の失敗の ERROR に、前の往復の脳を載せない", r.listener.snaps.drop(before).all { it.brain == null })
    }

    @Test fun cancellingClearsTheBrainFromTheSnapshot() {
        val r = Rig()
        r.brainIs(cloudDaidai)
        r.startTurn(turn())
        assertNotNull(r.last.brain)
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertNull(r.last.brain)
        assertFalse(r.last.micActive)
    }

    @Test fun theSnapshotToStringShowsOnlyTheClassOfTheBrain() {
        val s = VoiceSnapshot(brain = cloudDaidai)
        assertTrue(s.toString().contains("brain=BrainInfo(name=DAIDAI, egress=CLOUD)"))
    }
}
