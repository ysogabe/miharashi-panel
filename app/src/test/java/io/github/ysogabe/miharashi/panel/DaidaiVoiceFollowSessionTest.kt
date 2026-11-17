package io.github.ysogabe.miharashi.panel

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 本物の [VoiceSession](フェイクのブリッジ・マイク・再生、仮想時計)を [DaidaiVoice] に載せた、複数往復の通し(段階 2a-P の T2。AC2・AC3 の競合・AC4)。
 * ブリッジは本番と同じく [DeleteOnCloseBridge] で包み、会話の終わりのホストへの重複を 1 回に抑える。
 * ホストへの実際の通信は [VoiceConversationScenarioTest](MockWebServer)。
 */
class DaidaiVoiceFollowSessionTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")
    private val h1 = "AAAAAAAAAAAAAAAAAAAAAA"
    private val h5 = "EEEEEEEEEEEEEEEEEEEEEE"
    private val h9 = "IIIIIIIIIIIIIIIIIIIIII"

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private inner class Rigged {
        val log = mutableListOf<String>()
        val sim = Sim()
        val fake = FakeBridge(sim, log)
        val bridge = DeleteOnCloseBridge(fake)
        val worker = FakeWorker(sim, fake).also { fake.worker = it }
        val audio = FakeAudio(sim, log)
        val player = FakePlayer(log)
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()
        var tones = 0
        var followTones = 0
        var presence = GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT)
        val policy = AutoSwitchPolicy(AmbientLightGate(sample = { LuxSample(100f) }, config = { GateConfig.from(configured) }))

        /** 往復ごとの 201 の `conversation`(n は作成の通し番号、1 から)。null なら古いホスト(`conversation` なし) */
        var conversationFor: (Int) -> ConversationInfo? = { ConversationInfo(h1, true, true) }

        /** 往復ごとの作成の遅延(ms) */
        var latencyFor: (Int) -> Long = { 0 }

        val voice = DaidaiVoice(
            pages, policy, { configured },
            VoiceRuntimeFactory { _, listener ->
                val session = VoiceSession(audio, bridge, CueRejectingPlayer(player), sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0")
                object : VoiceRuntime {
                    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
                    override fun retry() = session.retry()
                    override fun start(request: TurnRequest) = session.start(request)
                    override fun retry(request: TurnRequest) = session.retry(request)
                    // 本番(AndroidVoiceRuntime)はスレッドプールで送る。ここは同期で、DeleteOnCloseBridge が重複を抑える
                    override fun endConversation(handle: String) = bridge.endConversation(handle)
                    override fun cancel() = session.cancel()
                    override fun stopMicNow() = session.stopMicNow()
                    override fun close() { session.cancel(); session.stopMicNow() }
                }
            },
            sim, { rendered += it },
            object : StartSound {
                override fun play() { tones++ }
                override fun playFollow() { followTones++ }
            },
            { sim.now }, { presence },
        )

        init {
            // 作成の要求はホストに届いている(遅い 201)。通信の中断では止められない(abortable = false)ので、遅れて届く
            fake.createReply = { n -> ok(SessionInfo("s-$n", fake.hostLimits, conversationFor(n)), latencyMs = latencyFor(n), abortable = latencyFor(n) == 0L) }
            pages.addListener(object : PageListener {
                override fun onSettled(page: Page, reason: PageReason) {
                    if (page == Page.B) voice.onEntered(DaidaiEntry(reason, false, openMic = true, noticeId = null, configured = true))
                    else voice.onLeft()
                }
            })
        }

        val ui get() = rendered.last()

        fun enter() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.B, sim.now)
            pages.animationEnded(sim.now)
        }

        fun leave() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.A, sim.now)
            sim.settle()
            pages.animationEnded(sim.now)
            sim.settle()
        }

        /** B に入って最初の往復のマイクが開くまで */
        fun startFirstTurn() {
            enter()
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
            assertTrue("最初の往復のマイクが開く", audio.recording)
        }

        /** 話し始め(開始音の保護を過ぎる) → 返答 → 再生の完了。完了の時刻を返す */
        fun reply(): Long {
            audio.feed(1) // 録音中であること(保護時間の中)
            var left = 440
            while (left > 0) { audio.feed(20); sim.advance(20); left -= 20 }
            fake.emit(BridgeEvent.Reply(fake.nextSeq(), 0, "こんにちは", ReplyAudioInfo("wav", 24_000, 2000, 4200), null, true, "talking"))
            sim.settle()
            player.finish(true)
            sim.settle()
            return sim.now
        }

        fun hostError(code: String) {
            fake.emit(BridgeEvent.Error(fake.nextSeq(), code, null))
            sim.settle()
        }
    }

    // ---- 3 往復(AC2・AC4)

    @Test fun threeTurnsRunThroughTheRealSessionWithTheEchoWaitAndTheStartGuard() {
        val r = Rigged()
        r.startFirstTurn()
        assertEquals(ConversationParam(null), r.fake.created[0].conversation)
        assertEquals(VoiceLimits.NO_SPEECH_DEFAULT_S, r.fake.created[0].noSpeechS)
        assertEquals("最初の往復の開始音", 1, r.tones)
        assertEquals(0, r.followTones)
        val playedAt = r.reply()
        assertEquals(1, r.audio.startCount)
        assertFalse("再生の完了のあと、マイクは閉じている", r.audio.recording)
        // AC4: 再生の完了 + 残響の待ち(300 ms)より前に、マイクの start も作成も 0 回
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS - 1)
        assertEquals("残響の待ちの間は作成も開始も無い", 1, r.fake.created.size)
        assertEquals(1, r.audio.startCount)
        r.sim.advance(1)
        assertEquals(playedAt + DaidaiVoice.ECHO_WAIT_MS, r.sim.now)
        assertEquals("残響の待ちが明けて 2 往復目を作成", 2, r.fake.created.size)
        assertEquals(ConversationParam(h1), r.fake.created[1].conversation)
        assertEquals("続きの待ちは 6 秒", VoiceLimits.FOLLOW_NO_SPEECH_S, r.fake.created[1].noSpeechS)
        assertEquals(2, r.audio.startCount)
        assertEquals("続きの開始音は別の音", 1, r.followTones)
        assertEquals("最初の往復の音は増えない", 1, r.tones)
        // AC4: 続きの往復でも、開いた直後の 400 ms の録音は送らない
        val sent = r.fake.chunks.size
        var left = 380
        while (left > 0) { r.audio.feed(20, amp = 7000); r.sim.advance(20); left -= 20 }
        assertEquals("開いた直後の保護時間の録音は送られない", sent, r.fake.chunks.size)
        r.reply()
        assertTrue("保護時間のあとの録音は送られる", r.fake.chunks.size > sent)
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals(3, r.fake.created.size)
        assertEquals(ConversationParam(h1), r.fake.created[2].conversation)
        // 3 往復目: 続きの無発話で静かに終わる(ERROR を出さない)
        r.sim.advance(500)
        r.hostError("no_speech")
        r.sim.advance(VoiceLimits.ERROR_DISPLAY_MS * 2)
        assertTrue("ERROR の表示が一度も出ない", r.rendered.none { it.voice == VoiceState.ERROR })
        assertEquals(VoiceState.IDLE, r.ui.voice)
        assertEquals("会話の終わりの通知は 1 回", listOf(h1), r.fake.conversationEnds)
        assertEquals("往復ごとのセッションを DELETE", listOf("s-1", "s-2", "s-3"), r.fake.cancels)
        assertFalse("会話が終われば無操作タイマーを止めない", r.pages.sessionActive)
        r.sim.advance(10_000)
        assertEquals("もう作らない", 3, r.fake.created.size)
    }

    @Test fun anOldHostWithoutAConversationStillRunsSeveralTurnsAndSendsNoEnd() {
        val r = Rigged()
        r.conversationFor = { null }
        r.startFirstTurn()
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals(ConversationParam(null), r.fake.created[1].conversation)
        assertEquals(VoiceLimits.FOLLOW_NO_SPEECH_S, r.fake.created[1].noSpeechS)
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals(3, r.fake.created.size)
        r.leave()
        assertTrue("ハンドルが無ければ終わりの通知は送らない", r.fake.conversationEnds.isEmpty())
    }

    @Test fun aFollowCreateFailureShowsAnErrorAndEndsTheConversation() {
        val r = Rigged()
        r.startFirstTurn()
        r.reply()
        r.fake.createReply = { n ->
            if (n == 2) failWith(bridgeError(BridgeFailure.ASR_UNAVAILABLE, 503, "asr_unavailable"))
            else ok(SessionInfo("s-$n", r.fake.hostLimits, ConversationInfo(h1, true, true)))
        }
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals(VoiceState.ERROR, r.ui.voice)
        assertEquals(VoiceErrorCode.ASR_UNAVAILABLE, r.ui.error)
        assertEquals("会話は終わる(自動で作り直さない)", listOf(h1), r.fake.conversationEnds)
        r.sim.advance(30_000)
        assertEquals(2, r.fake.created.size)
    }

    @Test fun hostContinueFalseStopsTheNextTurn() {
        val r = Rigged()
        r.conversationFor = { ConversationInfo(h1, true, false) }
        r.startFirstTurn()
        r.reply()
        r.sim.advance(10_000)
        assertEquals(1, r.fake.created.size)
        assertEquals(listOf(h1), r.fake.conversationEnds)
    }

    // ---- 競合(設計書 §5.4 の 6 つ。どれも取り消した会話の録音は 0 回。6 つ目は別のファイル)

    @Test fun race1_leavingWhileTheEndNotificationIsStillQueued() {
        val r = Rigged()
        r.startFirstTurn()
        r.audio.feed(1)
        var left = 440
        while (left > 0) { r.audio.feed(20); r.sim.advance(20); left -= 20 }
        r.fake.emit(BridgeEvent.Reply(r.fake.nextSeq(), 0, "こんにちは", ReplyAudioInfo("wav", 24_000, 2000, 4200), null, true, "talking"))
        r.sim.settle()
        r.player.finish(true) // 往復の終わり(onEnded の main への通知はまだキューの中)
        r.leave() // 通知を処理する前に離脱
        r.sim.advance(10_000)
        assertEquals("続きの作成 0 回", 1, r.fake.created.size)
        assertEquals(1, r.audio.startCount)
        assertEquals(listOf(h1), r.fake.conversationEnds)
    }

    @Test fun race2_cancelDuringTheEchoWait() {
        val r = Rigged()
        r.startFirstTurn()
        r.reply()
        r.sim.advance(100)
        r.leave()
        r.sim.advance(10_000)
        assertEquals(1, r.fake.created.size)
        assertEquals(1, r.audio.startCount)
        assertEquals(listOf(h1), r.fake.conversationEnds)
    }

    @Test fun race3_cancelWhileTheStartIsWaitingInTheSessionQueue() {
        val r = Rigged()
        r.startFirstTurn()
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS - 1)
        r.sim.tick(1)
        assertTrue("残響の待ちが明けた(メインの予約だけ実行。start はセッションのキューの中)", r.sim.step())
        assertEquals("まだ作成の要求は出ていない", 1, r.fake.created.size)
        r.leave()
        r.sim.advance(10_000)
        assertEquals("作成 0 回", 1, r.fake.created.size)
        assertEquals(1, r.audio.startCount)
        assertEquals(listOf(h1), r.fake.conversationEnds)
    }

    @Test fun race4_cancelAfterTheCreateRequestWasSentRunsTheLate201Cleanup() {
        val r = Rigged()
        r.latencyFor = { n -> if (n == 2) 800 else 0 }
        r.conversationFor = { n -> ConversationInfo(if (n == 2) h9 else h1, true, true) }
        r.startFirstTurn()
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals("作成の要求は送った", 2, r.fake.created.size)
        r.sim.advance(100)
        r.leave() // 201 より前に離脱
        assertEquals(listOf(h1), r.fake.conversationEnds)
        r.sim.advance(2_000) // 遅い 201(新しいハンドル h9)が届く
        assertEquals("録音 0 回(1 往復目の 1 回だけ)", 1, r.audio.startCount)
        assertFalse(r.audio.recording)
        assertEquals("遅れて届いた 201 のセッションを DELETE", 1, r.fake.cancels.count { it == "s-2" })
        assertEquals("会話の終わりの通知は 1 ハンドルにつき 1 回", listOf(h1, h9), r.fake.conversationEnds)
        assertEquals(2, r.fake.created.size)
    }

    @Test fun race4b_theLate201IsDeliveredBeforeTheCancelIsProcessed() {
        val r = Rigged()
        r.conversationFor = { n -> ConversationInfo(if (n == 2) h9 else h1, true, true) }
        r.startFirstTurn()
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS - 1)
        r.sim.tick(1)
        r.sim.step() // 続きの予約(メイン)
        r.sim.step() // doStart(セッションのスレッド): 作成の要求を出す
        assertEquals(2, r.fake.created.size)
        r.leave() // 201 の配送がセッションのキューに積まれたあとで取消が来る
        r.sim.advance(5_000)
        assertEquals(1, r.audio.startCount)
        assertEquals("セッションを DELETE", 1, r.fake.cancels.count { it == "s-2" })
        assertEquals("どのハンドルも 1 回ずつ", listOf(h1, h9), r.fake.conversationEnds)
    }

    @Test fun race5_reEnteringRightAfterLeavingIsNotMovedByTheOldReservationOrTheOld201() {
        val r = Rigged()
        r.latencyFor = { n -> if (n == 2) 800 else 0 }
        r.conversationFor = { n ->
            when (n) { 1 -> ConversationInfo(h1, true, true); 2 -> ConversationInfo(h9, true, true); else -> ConversationInfo(h5, true, true) }
        }
        r.startFirstTurn()
        r.reply()
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS) // 古い会話の 2 往復目の作成(遅い)
        r.sim.advance(50)
        r.leave()
        r.enter() // 離脱してすぐの再入場
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals("新しい会話の 1 往復目", 3, r.fake.created.size)
        assertEquals(ConversationParam(null), r.fake.created[2].conversation)
        assertTrue(r.audio.recording)
        assertEquals(2, r.audio.startCount)
        r.sim.advance(1_000) // 古い 201(h9)が届く
        assertTrue("古い 201 が新しい会話のマイクを止めない", r.audio.recording)
        assertEquals(1, r.fake.cancels.count { it == "s-2" })
        assertTrue("古い会話のハンドルは終わらせる", r.fake.conversationEnds.containsAll(listOf(h1, h9)))
        assertFalse("新しい会話のハンドルは終わらせない", r.fake.conversationEnds.contains(h5))
        r.reply() // 新しい会話の続きは、新しいハンドルで
        r.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        assertEquals(4, r.fake.created.size)
        assertEquals("古い 201 のハンドルで上書きしない", ConversationParam(h5), r.fake.created[3].conversation)
    }

    // 競合 6(往復の終わりの通知の重複)は DaidaiVoiceConversationTest.aDuplicateEndNotificationOfTheSameTurnContinuesOnlyOnce(セッションは終了を 1 回しか通知しないので、通知の重複はリスナーを直接呼んで再現する)

    // ---- ゲート(AC6)を本物のセッションで

    @Test fun anAbsentGateEndsTheConversationWithoutCreatingTheNextTurn() {
        val r = Rigged()
        r.startFirstTurn()
        r.presence = GateVerdict(Presence.ABSENT, AmbientLightGate.REASON_DARK)
        r.reply()
        r.sim.advance(10_000)
        assertEquals(1, r.fake.created.size)
        assertEquals(listOf(h1), r.fake.conversationEnds)
    }
}
