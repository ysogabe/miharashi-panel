package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 呼び出しを記録するだけの [VoiceRuntime]。セッションのスレッドからの通知は [listener] を直接呼んで再現する */
private class ConvRuntime(val settings: DaidaiSettings, val listener: VoiceSessionListener) : VoiceRuntime {
    val log = mutableListOf<String>()
    val turnStarts = mutableListOf<TurnRequest>()
    val turnRetries = mutableListOf<TurnRequest>()
    val conversationEnds = mutableListOf<String>()
    var cancels = 0
    var closed = false

    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) { log += "oldStart" }
    override fun retry() { log += "oldRetry" }
    override fun start(request: TurnRequest) { log += "start"; turnStarts += request }
    override fun retry(request: TurnRequest) { log += "retry"; turnRetries += request }
    override fun endConversation(handle: String) { log += "endConversation"; conversationEnds += handle }
    override fun cancel() { log += "cancel"; cancels++ }
    override fun stopMicNow() { log += "stopMic" }
    override fun close() { log += "close"; closed = true }
}

private const val H1 = "AAAAAAAAAAAAAAAAAAAAAA"
private const val H2 = "BBBBBBBBBBBBBBBBBBBBBB"
private const val H3 = "CCCCCCCCCCCCCCCCCCCCCC"

/**
 * 会話の制御(段階 2a-P の T2。設計書(段階 2)§5、AC1・AC2・AC5・AC6)。純 JVM: 仮想時計 [Sim] がメインで、実物の
 * [PageController] / [DaidaiController] / [AutoSwitchPolicy] を使い、セッション一式は [ConvRuntime](往復の終わりなどは listener を直接呼んで再現)。
 * 本物の `VoiceSession` と通信を載せたものは [VoiceConversationScenarioTest]・[DaidaiVoiceSessionTest]。
 */
class DaidaiVoiceConversationTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    private inner class Harness(var settings: DaidaiSettings = configured) {
        val sim = Sim()
        val pages = PageController()
        val runtimes = mutableListOf<ConvRuntime>()
        var tones = 0
        var followTones = 0
        var toneThrows = false
        var presence = GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT)
        var presenceCalls = 0
        lateinit var voice: DaidaiVoice
        val controller: DaidaiController = DaidaiController(pages, { settings }, { voice.onBaseUi(it) }, { }, { sim.now })
        val rt get() = runtimes.last()
        private var rev = 1L
        private var noticeSeq = 0

        init {
            voice = DaidaiVoice(
                pages, controller.policy, { settings },
                VoiceRuntimeFactory { s, l -> ConvRuntime(s, l).also { runtimes += it } },
                sim, { }, object : StartSound {
                    override fun play() { tones++; if (toneThrows) throw IllegalStateException("tone") }
                    override fun playFollow() { followTones++; if (toneThrows) throw IllegalStateException("tone") }
                },
                { sim.now }, { presenceCalls++; presence },
            )
            controller.entryListener = voice
        }

        fun ready() {
            controller.start(PanelState(), "ep")
            controller.onLux(180f)
            feed(status(rev))
        }

        fun feed(status: KioskStatus) = controller.onKioskState(PanelState().withStatus(status, sim.now))

        fun swipeToB() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.B, sim.now)
            pages.animationEnded(sim.now)
        }

        fun swipeToA() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.A, sim.now)
            pages.animationEnded(sim.now)
        }

        /** スワイプで B に入り、最初の往復が始まるまで進める */
        fun startSwipe() {
            ready()
            swipeToB()
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
            assertEquals(1, rt.turnStarts.size)
        }

        /** 通知の自動切替で B に入り、最初の往復が始まるまで進める(A から) */
        fun startAuto(id: String = "n${++noticeSeq}") {
            rev++
            feed(status(rev, notice(id)))
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
            assertTrue("通知の自動切替で最初の往復が始まる", rt.turnStarts.isNotEmpty())
        }

        fun snapshot(s: VoiceSnapshot) {
            rt.listener.onSnapshot(s)
            sim.settle()
        }

        val lastReq: TurnRequest get() = rt.turnStarts.last()

        /** 作成の応答に会話のハンドルがあった(今の往復の印で) */
        fun conversation(handle: String?, cont: Boolean = true, tag: TurnTag = lastReq.tag) {
            rt.listener.onConversation(tag, ConversationInfo(handle, true, cont))
            sim.settle()
        }

        fun micOpened(req: TurnRequest = lastReq) {
            rt.listener.onMicOpened(req.autoStarted, req.follow, req.tag)
            sim.settle()
        }

        /** 今(または指定)の往復が終わった */
        fun endTurn(outcome: TurnOutcome = TurnOutcome.PLAYED, reason: String? = null, req: TurnRequest = lastReq) {
            val r = reason ?: when (outcome) {
                TurnOutcome.PLAYED, TurnOutcome.TAPPED, TurnOutcome.TEXT_ONLY, TurnOutcome.PLAYBACK_FAILED,
                TurnOutcome.PLAYBACK_TIMEOUT, TurnOutcome.SESSION_TIMEOUT -> "done"
                TurnOutcome.CANCELLED -> "cancelled"
                TurnOutcome.ERROR -> "no_speech"
            }
            rt.listener.onEnded(req.autoStarted, r, outcome, req.tag)
            sim.settle()
        }

        /** 往復の終わり → 残響の待ちが明けて、次の往復が始まる */
        fun playedAndFollow() {
            val before = rt.turnStarts.size
            endTurn(TurnOutcome.PLAYED)
            sim.advance(DaidaiVoice.ECHO_WAIT_MS)
            assertEquals(before + 1, rt.turnStarts.size)
        }

        /** 次の自動起動の判定: B を離れ、クールダウンを過ぎてから新しい通知が来たときのマイクの歯止め */
        fun nextAutoDenial(advanceMs: Long = AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 1): MicDenial? {
            if (pages.settledPage == Page.B) swipeToA()
            sim.advance(advanceMs)
            controller.onLux(180f)
            rev++
            feed(status(rev, notice("probe-$rev")))
            val r = controller.policy.lastResult!!
            assertEquals(AutoSwitchAction.SWITCH, r.action)
            return r.micDenial
        }

        private fun notice(id: String) =
            JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", 3).put("priority", "normal")

        private fun status(rev: Long, vararg ns: JSONObject): KioskStatus {
            val d = JSONObject().put("notices", JSONArray(ns.toList())).put("ack", true).put("unseen", ns.size).put("rev", rev)
            return KioskStatus.parse(JSONObject(fixture("status-photo.json")).put("daidai", d).toString())
        }

        private fun fixture(name: String) = this@DaidaiVoiceConversationTest.javaClass.getResource("/fixtures/$name")!!.readText()
    }

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private fun snap(
        state: VoiceState = VoiceState.IDLE, active: Boolean = false, mic: Boolean = false, error: VoiceErrorCode? = null,
    ) = VoiceSnapshot(state = state, error = error, micActive = mic, active = active)

    // ---- 往復の要求の形

    @Test fun theFirstTurnAsksForANewConversationWithTheStartValues() {
        val h = Harness()
        h.startSwipe()
        val r = h.lastReq
        assertEquals(StartReason.SWIPE, r.reason)
        assertNull(r.noticeId)
        assertFalse(r.autoStarted)
        assertEquals("1 往復目は conversation: {}(ハンドルなし)", ConversationParam(null), r.conversation)
        assertFalse(r.follow)
        assertEquals(VoiceLimits.NO_SPEECH_DEFAULT_S, r.noSpeechS)
        assertEquals(1, r.tag.turn)
        assertTrue("世代は 1 以上(NONE と区別できる)", r.tag.conversationGen > 0)
        assertTrue("旧い呼び方は使わない", h.rt.log.none { it.startsWith("old") })
    }

    @Test fun threeTurnsCarryTheHandleAndTheFollowFlagsAndTheConversationEndsOnce() {
        val h = Harness()
        h.startSwipe()
        val gen = h.lastReq.tag.conversationGen
        h.conversation(H1)
        h.micOpened()
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS - 1)
        assertEquals("残響の待ちの間は始めない", 1, h.rt.turnStarts.size)
        h.sim.advance(1)
        val t2 = h.rt.turnStarts[1]
        assertEquals(ConversationParam(H1), t2.conversation)
        assertTrue(t2.follow)
        assertEquals("続きの待ちは 6 秒", VoiceLimits.FOLLOW_NO_SPEECH_S, t2.noSpeechS)
        assertEquals(TurnTag(gen, 2), t2.tag)
        assertEquals(StartReason.SWIPE, t2.reason)
        assertNull(t2.noticeId)
        assertFalse(t2.autoStarted)
        h.conversation(H1)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        val t3 = h.rt.turnStarts[2]
        assertEquals(TurnTag(gen, 3), t3.tag)
        assertEquals(ConversationParam(H1), t3.conversation)
        assertTrue("会話の間は終わりの通知を送らない", h.rt.conversationEnds.isEmpty())
        h.endTurn(TurnOutcome.ERROR, "no_speech") // 続きの無発話: 静かに終わる
        h.sim.advance(5_000)
        assertEquals(3, h.rt.turnStarts.size)
        assertEquals("会話の終わりの通知は 1 回", listOf(H1), h.rt.conversationEnds)
        assertFalse("会話が終われば無操作タイマーを止めない", h.pages.sessionActive)
    }

    @Test fun anOldHostWithoutAHandleKeepsSendingAnEmptyConversationAndNeverSendsAnEnd() {
        val h = Harness()
        h.startSwipe()
        // 201 に conversation が無い: onConversation は呼ばれない
        h.playedAndFollow()
        assertEquals(ConversationParam(null), h.lastReq.conversation)
        assertTrue(h.lastReq.follow)
        h.endTurn(TurnOutcome.ERROR, "no_speech")
        h.swipeToA()
        assertTrue("ハンドルが無ければ終わりの通知は 0 回", h.rt.conversationEnds.isEmpty())
    }

    // ---- 続けるかの判定(AC1 の結果の表、AC2)

    @Test fun onlyPlayedAndTappedContinue() {
        for (outcome in TurnOutcome.values()) {
            val h = Harness()
            h.startSwipe()
            h.conversation(H1)
            h.endTurn(outcome)
            h.sim.advance(DaidaiVoice.ECHO_WAIT_MS + 1_000)
            if (outcome.canContinue) {
                assertEquals("$outcome は続く", 2, h.rt.turnStarts.size)
                assertTrue(h.rt.conversationEnds.isEmpty())
            } else {
                assertEquals("$outcome は続かない", 1, h.rt.turnStarts.size)
                assertEquals("$outcome: 会話の終わりの通知は 1 回", listOf(H1), h.rt.conversationEnds)
                assertFalse(h.pages.sessionActive)
            }
        }
    }

    @Test fun aDuplicateEndNotificationOfTheSameTurnContinuesOnlyOnce() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        val first = h.lastReq
        h.endTurn(TurnOutcome.PLAYED, req = first)
        h.endTurn(TurnOutcome.PLAYED, req = first)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals("続きは 1 回だけ", 2, h.rt.turnStarts.size)
        h.endTurn(TurnOutcome.PLAYED, req = first) // 古い往復の通知が遅れて来ても動かさない
        h.sim.advance(5_000)
        assertEquals(2, h.rt.turnStarts.size)
    }

    @Test fun afterTheFifthTurnThereIsNoSixthAndTheConversationEnds() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        repeat(DaidaiVoice.MAX_TURNS - 1) { h.playedAndFollow() }
        assertEquals(DaidaiVoice.MAX_TURNS, h.rt.turnStarts.size)
        assertEquals(DaidaiVoice.MAX_TURNS, h.lastReq.tag.turn)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(10_000)
        assertEquals("6 回目の作成は無い", DaidaiVoice.MAX_TURNS, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun aTurnStartedJustBeforeTheAcceptanceDeadlineIsNotCutAndNoNextOneIsCreated() {
        val h = Harness()
        h.startSwipe()
        val t0 = h.sim.now
        h.conversation(H1)
        h.sim.advance(100_000)
        h.endTurn(TurnOutcome.PLAYED) // 往復 1 が 100 秒で終わる
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        h.sim.advance(DaidaiVoice.ACCEPT_WINDOW_MS - 1_000 - (h.sim.now - t0)) // 期限の 1 秒前
        // 往復 2 の終わり(期限の 1 秒前)→ 残響の待ちで 300 ms 後はまだ期限の前なので往復 3 が始まる
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertTrue(h.sim.now - t0 < DaidaiVoice.ACCEPT_WINDOW_MS)
        assertEquals("期限の直前に始めた往復は作られる", 3, h.rt.turnStarts.size)
        val cancelsBefore = h.rt.cancels
        h.sim.advance(60_000) // 期限を過ぎても、進行中の往復は切らない
        assertTrue(h.sim.now - t0 > DaidaiVoice.ACCEPT_WINDOW_MS)
        assertEquals("進行中の往復を切らない", cancelsBefore, h.rt.cancels)
        assertTrue("会話は続いたまま", h.pages.sessionActive)
        assertTrue(h.rt.conversationEnds.isEmpty())
        h.endTurn(TurnOutcome.PLAYED) // 往復 3 が終わった(期限のあと)
        h.sim.advance(10_000)
        assertEquals("次は作られない", 3, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
    }

    @Test fun theDeadlinePassingDuringTheEchoWaitStopsTheNextTurn() {
        val h = Harness()
        h.startSwipe()
        val t0 = h.sim.now
        h.conversation(H1)
        h.sim.advance(DaidaiVoice.ACCEPT_WINDOW_MS - 100 - (h.sim.now - t0)) // 期限の 100 ms 前
        h.endTurn(TurnOutcome.PLAYED) // 判定の時点では期限の前 → 残響の待ちを置く
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS) // 待ちの間に期限が来る
        assertEquals("待ちの明けでもう一度確かめる", 1, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun hostContinueFalseEndsTheConversationAfterThatTurn() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1, cont = false)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(10_000)
        assertEquals(1, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
    }

    @Test fun aSecondHandleFromTheHostReplacesTheFirstAndTheOldOneIsEnded() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.playedAndFollow()
        h.conversation(H2) // ホストが別のハンドルを返した
        assertEquals("古い方に終わりの通知", listOf(H1), h.rt.conversationEnds)
        h.playedAndFollow()
        assertEquals("新しい方を使う", ConversationParam(H2), h.lastReq.conversation)
        h.swipeToA()
        assertEquals(listOf(H1, H2), h.rt.conversationEnds)
    }

    // ---- 在席ゲート(AC6、S2-U9)

    @Test fun anAbsentGateEndsTheConversationInsteadOfCreatingTheNextTurn() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.presence = GateVerdict(Presence.ABSENT, AmbientLightGate.REASON_DARK)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(10_000)
        assertEquals("続きは作られない", 1, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
        assertTrue("ゲートは往復の終わりごとに見る", h.presenceCalls >= 1)
    }

    @Test fun anUnknownGateContinuesASwipeConversationAndEndsAnAutoStartedOne() {
        val swipe = Harness()
        swipe.startSwipe()
        swipe.presence = GateVerdict(Presence.UNKNOWN, AmbientLightGate.REASON_LUX_UNAVAILABLE)
        swipe.playedAndFollow()
        assertEquals("スワイプの会話は続く", 2, swipe.rt.turnStarts.size)

        val auto = Harness()
        auto.ready()
        auto.startAuto()
        assertTrue(auto.lastReq.autoStarted)
        auto.presence = GateVerdict(Presence.UNKNOWN, AmbientLightGate.REASON_LUX_UNAVAILABLE)
        auto.conversation(H1)
        auto.endTurn(TurnOutcome.PLAYED)
        auto.sim.advance(10_000)
        assertEquals("通知の自動起動の会話は続かない", 1, auto.rt.turnStarts.size)
        assertEquals(listOf(H1), auto.rt.conversationEnds)
    }

    @Test fun thePresenceGateIsCheckedOnEveryFollowDecision() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.playedAndFollow()
        h.playedAndFollow()
        assertEquals("往復の終わりごとに 1 回", 2, h.presenceCalls)
    }

    @Test fun anUnknownGateDoesNotStopAnErrorRetryConversation() {
        val h = Harness()
        h.startSwipe()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.endTurn(TurnOutcome.ERROR, "host_unreachable")
        h.voice.onErrorTapped()
        assertEquals(1, h.rt.turnRetries.size)
        h.presence = GateVerdict(Presence.UNKNOWN, AmbientLightGate.REASON_LUX_UNAVAILABLE)
        h.rt.listener.onSnapshot(snap(VoiceState.IDLE, active = true)); h.sim.settle()
        val retried = h.rt.turnRetries.single()
        h.rt.listener.onEnded(retried.autoStarted, "done", TurnOutcome.PLAYED, retried.tag)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals("再試行の会話は照度が取れなくても続く", 2, h.rt.turnStarts.size)
        assertEquals(TurnTag(retried.tag.conversationGen, 2), h.lastReq.tag)
    }

    // ---- 離脱(AC2。PanelActivity の結線を通す試験は PanelActivityConversationTest)

    @Test fun leavingBEndsTheConversationOnceAndNeverCreatesAFollowUp() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.endTurn(TurnOutcome.PLAYED) // 残響の待ちの間
        h.swipeToA()
        h.voice.cancel() // 離脱では cancel が何度も呼ばれる
        h.voice.cancel()
        h.sim.advance(10_000)
        assertEquals("続きの作成 0 回", 1, h.rt.turnStarts.size)
        assertEquals("終わりの通知は 1 回", listOf(H1), h.rt.conversationEnds)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun theEndNotificationComesBeforeRuntimeCancelAndBeforeClose() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.voice.cancel()
        val log = h.rt.log
        assertTrue("世代を進めて終わりの通知 → rt.cancel", log.indexOf("endConversation") in 0 until log.lastIndexOf("cancel"))
        h.voice.stop()
        assertTrue("close より前", log.indexOf("endConversation") < log.indexOf("close"))
        assertEquals(1, h.rt.conversationEnds.size)
    }

    @Test fun aLateEndNotificationAfterTheCancelDoesNotStartAnything() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        val first = h.lastReq
        h.voice.cancel()
        h.endTurn(TurnOutcome.PLAYED, req = first) // 取消のあとに届いた古い往復の終わり(離脱での 終わりの通知を待っている間)
        h.sim.advance(10_000)
        assertEquals(1, h.rt.turnStarts.size)
        assertEquals(listOf(H1), h.rt.conversationEnds)
    }

    @Test fun aFollowUpReservationCancelledByLeavingNeverFiresEvenIfReEnteredQuickly() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        h.endTurn(TurnOutcome.PLAYED)
        val gen1 = h.lastReq.tag.conversationGen
        h.sim.advance(100) // 残響の待ちの途中
        h.swipeToA()
        h.swipeToB() // 離脱してすぐの再入場
        h.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        val starts = h.rt.turnStarts
        assertEquals("古い予約は動かない。新しい会話の 1 往復目だけ", 2, starts.size)
        assertTrue(starts[1].tag.conversationGen > gen1)
        assertEquals("新しい会話は {} から", ConversationParam(null), starts[1].conversation)
        assertFalse(starts[1].follow)
        h.sim.advance(10_000)
        assertEquals(2, starts.size)
        assertEquals("古い会話の終わりの通知は 1 回", listOf(H1), h.rt.conversationEnds)
    }

    @Test fun aLate201OfAnOldConversationNeverOverwritesTheNewHandleAndIsEnded() {
        val h = Harness()
        h.startSwipe()
        val oldTag = h.lastReq.tag
        h.swipeToA() // ハンドルを受け取る前に離脱
        h.swipeToB()
        h.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        h.conversation(H2) // 新しい会話のハンドル
        h.conversation(H1, tag = oldTag) // 遅れて届いた古い会話の 201
        assertEquals("古い 201 のハンドルは終わらせる", listOf(H1), h.rt.conversationEnds)
        h.playedAndFollow()
        assertEquals("新しい会話のハンドルを上書きしない", ConversationParam(H2), h.lastReq.conversation)
        h.conversation(H1, tag = oldTag) // 重複して届いても、2 回送らない
        assertEquals(listOf(H1), h.rt.conversationEnds)
    }

    @Test fun aHandleOfAnEndedConversationWithNoNewConversationIsEndedToo() {
        val h = Harness()
        h.startSwipe()
        val tag = h.lastReq.tag
        h.voice.cancel() // 201 を待っている間に離脱(ハンドルは未取得)
        assertTrue(h.rt.conversationEnds.isEmpty())
        h.conversation(H1, tag = tag) // 作成の要求を送ったあとの取消: 遅れて届く 201
        assertEquals("遅れた 201 のハンドルに終わりの通知", listOf(H1), h.rt.conversationEnds)
    }

    @Test fun aConversationNotificationFromTheDisposedRuntimeIsIgnored() {
        val h = Harness()
        h.startSwipe()
        val first = h.rt
        h.swipeToA()
        h.settings = configured.copy(url = "http://192.168.0.200:8800") // 接続先の変更
        h.voice.onResume() // 古いセッションを捨てる
        h.swipeToB()
        h.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals(2, h.runtimes.size)
        assertTrue(first.closed)
        val second = h.rt
        h.conversation(H2)
        first.listener.onConversation(TurnTag(1, 1), ConversationInfo(H3, true, true)) // 破棄済みの runtime の通知
        first.listener.onEnded(false, "done", TurnOutcome.PLAYED, TurnTag(1, 1))
        h.sim.advance(5_000)
        assertEquals("破棄済みの runtime の通知は今の会話を動かさない", 1, second.turnStarts.size)
        h.swipeToA()
        assertEquals(listOf(H2), second.conversationEnds)
    }

    @Test fun rebuildingTheRuntimeEndsTheConversationBeforeClosing() {
        val h = Harness()
        h.startSwipe()
        h.conversation(H1)
        val first = h.rt
        h.settings = configured.copy(token = "bridge-token-other-0123456789")
        h.voice.onResume() // 接続先が変わっていたら古いセッションを捨てる
        assertEquals(listOf(H1), first.conversationEnds)
        assertTrue(first.log.indexOf("endConversation") < first.log.indexOf("close"))
    }

    @Test fun aHandleWhoseNotificationHasNotReachedMainYetIsStillEndedBeforeClose() {
        val h = Harness()
        h.startSwipe()
        // セッションのスレッドが onConversation を呼んで main へ post したが、まだ処理されていない間に背面になった
        h.rt.listener.onConversation(h.lastReq.tag, ConversationInfo(H1, true, true))
        h.voice.stop()
        h.sim.settle()
        assertEquals("close より前に 1 回", listOf(H1), h.rt.conversationEnds)
        assertTrue(h.rt.log.indexOf("endConversation") < h.rt.log.indexOf("close"))
    }

    // ---- 無操作タイマー(AC2)

    @Test fun theIdleTimerStaysStoppedBetweenTurnsAndRestartsFromTheConversationEnd() {
        val h = Harness()
        h.pages.idleMs = 10_000
        h.startSwipe()
        h.conversation(H1)
        h.snapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        h.snapshot(snap(VoiceState.IDLE, active = false)) // 往復の終わりの表示(DONE)
        var t = h.sim.now
        h.endTurn(TurnOutcome.PLAYED)
        // 残響の待ち(300 ms)と作成の待ちを含む 1 分間、進まない
        repeat(60) { t += 1_000; assertFalse(h.pages.tick(t)) }
        h.sim.advance(60_000)
        assertEquals("続きの往復が始まっている(会話が続く)", 2, h.rt.turnStarts.size)
        h.endTurn(TurnOutcome.ERROR, "no_speech") // 続きの無発話で会話が終わる
        assertFalse(h.pages.sessionActive)
        t += 1_000; assertFalse("会話の終わりから数え直す", h.pages.tick(t))
        t += 9_000; assertFalse(h.pages.tick(t))
        t += 1_000
        assertTrue("会話の終わりから設定の秒数で A に戻る", h.pages.tick(t))
    }

    // ---- 開始音(S2-U10)

    @Test fun theFollowTurnPlaysTheOtherToneAndTheFirstTurnPlaysTheNormalOne() {
        val h = Harness()
        h.startSwipe()
        h.micOpened()
        assertEquals(1, h.tones)
        assertEquals(0, h.followTones)
        h.conversation(H1)
        h.playedAndFollow()
        h.micOpened()
        assertEquals("最初の往復の音は増えない", 1, h.tones)
        assertEquals("続きの往復の音", 1, h.followTones)
    }

    @Test fun aToneFailureNeverPropagates() {
        val h = Harness()
        h.startSwipe()
        h.toneThrows = true
        h.micOpened() // 例外が出ない(セッションのスレッドで投げるとセッションが終わる)
        h.conversation(H1)
        h.playedAndFollow()
        h.micOpened()
        assertEquals(1, h.tones)
        assertEquals(1, h.followTones)
    }

    // ---- 再試行(新しい会話)

    @Test fun anErrorTapStartsANewConversationWithTheSameReasonAndNoticeAndFinishesTheOldOne() {
        val h = Harness()
        h.ready()
        h.startAuto("n-retry")
        val first = h.lastReq
        assertEquals(StartReason.NOTIFICATION, first.reason)
        assertEquals("n-retry", first.noticeId)
        h.conversation(H1)
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.endTurn(TurnOutcome.ERROR, "host_unreachable")
        assertEquals("前の会話の終わりの通知は済んでいる", listOf(H1), h.rt.conversationEnds)
        h.voice.onErrorTapped()
        val retry = h.rt.turnRetries.single()
        assertEquals("世代が進む", true, retry.tag.conversationGen > first.tag.conversationGen)
        assertEquals(1, retry.tag.turn)
        assertEquals("新しい会話({}) から", ConversationParam(null), retry.conversation)
        assertFalse(retry.follow)
        assertFalse("再試行は手で始めた会話", retry.autoStarted)
        assertEquals("reason は直前の値(通知)", StartReason.NOTIFICATION, retry.reason)
        assertEquals("notice_id は直前の値", "n-retry", retry.noticeId)
    }

    @Test fun anErrorTapRetryThatHitsTheLimitDoesNotCountAsAnAutoLimit() {
        val h = Harness()
        h.ready()
        h.startAuto()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.endTurn(TurnOutcome.ERROR, "host_unreachable")
        h.voice.onErrorTapped()
        val tag = h.rt.turnRetries.single().tag
        h.rt.listener.onConversation(tag, ConversationInfo(H1, true, true))
        h.rt.listener.onSnapshot(snap(VoiceState.IDLE, active = true))
        h.sim.settle()
        // 往復数の上限まで続ける
        var req = h.rt.turnRetries.single()
        repeat(DaidaiVoice.MAX_TURNS - 1) {
            h.rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, req.tag)
            h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
            req = h.rt.turnStarts.last()
        }
        h.rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, req.tag)
        h.sim.advance(1_000)
        assertEquals(DaidaiVoice.MAX_TURNS - 1 + 1, h.rt.turnStarts.size) // 自動起動の 1 往復 + 再試行の続き 4 回
        assertNull("再試行の会話は上限で終わっても数えない", h.nextAutoDenial())
    }

    @Test fun aSecondTapBeforeTheRetryStartedDoesNotCreateAnotherConversation() {
        val h = Harness()
        h.startSwipe()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.endTurn(TurnOutcome.ERROR, "host_unreachable")
        h.voice.onErrorTapped()
        h.voice.onErrorTapped() // まだ ERROR の表示のまま(進行中のスナップショットが届く前)
        assertEquals("再試行は 1 回", 1, h.rt.turnRetries.size)
        val tag = h.rt.turnRetries.single().tag
        h.rt.listener.onSnapshot(snap(VoiceState.IDLE, active = true)); h.sim.settle()
        h.rt.listener.onSnapshot(snap(VoiceState.IDLE, active = false)); h.sim.settle() // 続きの無発話(IDLE に終える)
        h.rt.listener.onEnded(false, "no_speech", TurnOutcome.ERROR, tag)
        h.sim.settle()
        assertFalse("往復の終わりで会話が終わる", h.pages.sessionActive)
    }

    @Test fun aRetryThatTheSessionIgnoredDoesNotLeaveTheConversationHoldingTheIdleTimer() {
        val h = Harness()
        h.startSwipe()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.endTurn(TurnOutcome.ERROR, "host_unreachable")
        h.voice.onErrorTapped()
        assertTrue(h.pages.sessionActive)
        // ERROR の表示が先に終わっていて、セッションは再試行を無視した: IDLE のスナップショットだけが届く
        h.snapshot(snap(VoiceState.IDLE, active = false))
        assertFalse(h.pages.sessionActive)
    }

    // ---- 自動切替の歯止め(AC5、R5、S2-U14)

    @Test fun followTurnsDoNotCountTowardTheCooldownOrTheNoSpeechHold() {
        val h = Harness()
        h.ready()
        h.startAuto()
        val t0 = h.sim.now
        h.micOpened() // 最初の往復: 歯止めに数える
        h.conversation(H1)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        h.sim.advance(60_000)
        h.micOpened() // 続きの往復(t0 + 60 秒): 数えない
        h.endTurn(TurnOutcome.ERROR, "no_speech") // 続きの無発話: 無発話の見送りにも数えない
        assertTrue(h.sim.now - t0 >= 60_000)
        // クールダウンは最初の往復(t0)から数える: t0 + 5 分 + 1 ms で明ける(続きの往復から数えるなら 4 分で足りない)
        assertNull(
            "続きの往復・続きの無発話は歯止めに数えない",
            h.nextAutoDenial(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 1 - (h.sim.now - t0)),
        )
    }

    @Test fun anAutoConversationEndedByTheTurnLimitHoldsTheNextAutoStartAndTheDiagnosticsSaysSo() {
        val h = Harness()
        h.ready()
        h.startAuto()
        h.conversation(H1)
        repeat(DaidaiVoice.MAX_TURNS - 1) { h.playedAndFollow() }
        h.endTurn(TurnOutcome.PLAYED) // 5 往復目の終わり: 上限
        h.sim.advance(1_000)
        assertEquals(DaidaiVoice.MAX_TURNS, h.rt.turnStarts.size)
        assertEquals(MicDenial.AFTER_CONVERSATION_LIMIT, h.nextAutoDenial())
        val line = AutoSwitchDiagnostics.format(h.controller.policy.lastResult!!)
        assertTrue(line, line.contains("直前の自動起動の会話が上限で終了"))
        assertFalse("無発話の文言とは別", line.contains("無発話"))
    }

    @Test fun anAutoConversationEndedByTheAcceptanceDeadlineHoldsTheNextAutoStart() {
        val h = Harness()
        h.ready()
        h.startAuto()
        h.conversation(H1)
        h.sim.advance(DaidaiVoice.ACCEPT_WINDOW_MS + 1)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(1_000)
        assertEquals(1, h.rt.turnStarts.size)
        assertEquals(MicDenial.AFTER_CONVERSATION_LIMIT, h.nextAutoDenial())
    }

    @Test fun anAutoConversationEndedByHostContinueFalseHoldsTheNextAutoStart() {
        val h = Harness()
        h.ready()
        h.startAuto()
        h.conversation(H1, cont = false)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(1_000)
        assertEquals(MicDenial.AFTER_CONVERSATION_LIMIT, h.nextAutoDenial())
    }

    @Test fun otherWaysAnAutoConversationEndsDoNotHoldTheNextAutoStart() {
        val ways = mapOf<String, (Harness) -> Unit>(
            "続きの無発話" to { h -> h.playedAndFollow(); h.endTurn(TurnOutcome.ERROR, "no_speech") },
            "エラー" to { h -> h.endTurn(TurnOutcome.ERROR, "host_unreachable") },
            "取消" to { h -> h.voice.cancel() },
            "続けない結果" to { h -> h.endTurn(TurnOutcome.TEXT_ONLY) },
            "不在" to { h -> h.presence = GateVerdict(Presence.ABSENT, "dark"); h.endTurn(TurnOutcome.PLAYED) },
            "照度が取れない" to { h -> h.presence = GateVerdict(Presence.UNKNOWN, "lux_unavailable"); h.endTurn(TurnOutcome.PLAYED) },
        )
        for ((name, end) in ways) {
            val h = Harness()
            h.ready()
            h.startAuto()
            h.conversation(H1)
            end(h)
            h.sim.advance(1_000)
            assertNull("$name は見送りに数えない", h.nextAutoDenial())
        }
    }

    @Test fun aSwipeConversationEndingAtTheLimitNeverHoldsTheNextAutoStart() {
        val swipe = Harness()
        swipe.startSwipe()
        swipe.conversation(H1)
        repeat(DaidaiVoice.MAX_TURNS - 1) { swipe.playedAndFollow() }
        swipe.endTurn(TurnOutcome.PLAYED)
        swipe.sim.advance(1_000)
        assertEquals(listOf(H1), swipe.rt.conversationEnds)
        assertNull("スワイプの会話は上限で終わっても数えない", swipe.nextAutoDenial())
    }

    @Test fun theLimitHoldIsReleasedByAManualSessionEndingNormally() {
        val h = Harness()
        h.ready()
        h.startAuto()
        h.conversation(H1, cont = false)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(1_000)
        h.swipeToA()
        // 手で始めたセッションが no_speech 以外で終わった(最初の往復): 解除
        h.swipeToB()
        h.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        h.endTurn(TurnOutcome.PLAYED)
        assertNull("手動のセッションが no_speech 以外で終われば解除", h.nextAutoDenial())
    }

    // ---- 画面外の想定外

    @Test fun nothingContinuesWhenTheSettingsAreClearedBeforeTheDecision() {
        val a = Harness()
        a.startSwipe()
        a.conversation(H1)
        a.settings = DaidaiSettings() // 判定の時点で設定が消えた
        a.endTurn(TurnOutcome.PLAYED)
        a.sim.advance(10_000)
        assertEquals(1, a.rt.turnStarts.size)
        assertEquals(listOf(H1), a.rt.conversationEnds)
    }
}
