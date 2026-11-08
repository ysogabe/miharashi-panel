package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

/**
 * 本物の `VoiceSession`(フェイクの BridgeApi / AudioSource / ReplyPlayer、仮想時計)を [DaidaiVoice] に載せた通し。
 * 結線の約束(開く条件・止める条件・表示の連動)が、実際のセッションの順序のもとでも守られることを確かめる。
 */
class DaidaiVoiceSessionTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    private class Runtime(val session: VoiceSession, val audio: FakeAudio) : VoiceRuntime {
        override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
        override fun retry() = session.retry()
        override fun cancel() = session.cancel()
        override fun stopMicNow() = audio.stop()
        override fun close() = session.cancel()
    }

    private inner class Rigged {
        val log = mutableListOf<String>()
        val sim = Sim()
        val bridge = FakeBridge(sim, log)
        val worker = FakeWorker(sim, bridge).also { bridge.worker = it }
        val audio = FakeAudio(sim, log)
        val player = FakePlayer(log)
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()
        var tones = 0
        val policy = AutoSwitchPolicy(AmbientLightGate(sample = { LuxSample(100f) }, config = { GateConfig.from(configured) }))
        val voice = DaidaiVoice(
            pages, policy, { configured },
            VoiceRuntimeFactory { _, listener ->
                Runtime(VoiceSession(audio, bridge, player, sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0"), audio)
            },
            sim, { rendered += it }, StartSound { tones++ }, { sim.now },
        )
        val ui get() = rendered.last()

        fun enter() {
            pages.addListener(object : PageListener {
                override fun onSettled(page: Page, reason: PageReason) {
                    if (page == Page.B) voice.onEntered(DaidaiEntry(reason, false, openMic = true, noticeId = null, configured = true))
                    else voice.onLeft()
                }
            })
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
    }

    @Test fun swipeOpensTheMicShowsTheIndicatorPlaysTheToneAndLeavingClosesEverything() {
        val r = Rigged()
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals("セッション作成 → マイク開始", 1, r.audio.startCount)
        assertTrue(r.audio.recording)
        assertTrue("マイク使用中の表示は実際の録音状態", r.ui.micActive)
        assertEquals(VoiceState.LISTENING, r.ui.voice)
        assertEquals("開始音", 1, r.tones)
        assertTrue(r.pages.sessionActive)

        r.leave()
        assertFalse("B を離れたらマイクは止まっている", r.audio.recording)
        assertFalse(r.ui.micActive)
        assertEquals(VoiceState.IDLE, r.ui.voice)
        assertFalse(r.pages.sessionActive)
        assertEquals("ホストのセッションを消す", listOf("s-1"), r.bridge.cancels)
    }

    @Test fun leavingDuringTheDebounceOpensNoMicAndNoSession() {
        val r = Rigged()
        r.enter()
        r.sim.advance(100)
        r.leave()
        r.sim.advance(2_000)
        assertEquals(0, r.audio.startCount)
        assertEquals(0, r.bridge.created.size)
    }

    @Test fun stopDuringPreparingNeverOpensTheMicAndDeletesTheLateSession() {
        val r = Rigged()
        r.bridge.createReply = { ok(SessionInfo("s-1", r.bridge.hostLimits), latencyMs = 3_000, abortable = false) } // 中断できない作成(応答が遅れて届く)
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals(1, r.bridge.created.size)
        r.voice.stop() // 作成中に背面へ
        r.sim.advance(5_000)
        assertEquals("作成前・作成中にはマイクを開かない", 0, r.audio.startCount)
        assertEquals("遅れて届いた作成は直ちに DELETE", listOf("s-1"), r.bridge.cancels)
    }

    @Test fun voiceStopClosesTheMicEvenIfTheSessionThreadWereStuck() {
        val r = Rigged()
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertTrue(r.audio.recording)
        // セッションのスレッドを介さない stopMicNow が、cancel の処理(post)より前に録音を止める
        r.voice.cancel()
        assertFalse("post された cancel が処理される前に、マイクは止まっている", r.audio.recording)
        r.sim.settle()
        assertFalse(r.audio.recording)
    }

    @Test fun micDeniedShowsTheGuideAndNeverRetriesByItself() {
        val r = Rigged()
        r.audio.startError = VoiceErrorCode.MIC_PERMISSION
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals(VoiceState.ERROR, r.ui.voice)
        assertEquals(SetupGuide.MIC_PERMISSION, r.ui.guide)
        assertFalse(r.pages.sessionActive)
        r.sim.advance(60_000)
        assertEquals("自動で再試行しない", 1, r.audio.startCount)
    }

    @Test fun tapOnErrorStartsANewSessionAsAUserAction() {
        val r = Rigged()
        r.audio.startError = VoiceErrorCode.MIC_UNAVAILABLE
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals(VoiceState.ERROR, r.ui.voice)
        r.audio.startError = null
        r.voice.onErrorTapped()
        r.sim.settle()
        assertEquals(2, r.bridge.created.size)
        assertEquals(VoiceState.LISTENING, r.ui.voice)
        assertTrue(r.audio.recording)
        r.leave()
        assertFalse(r.audio.recording)
    }

    @Test fun theReplyIsPlayedWithoutOpeningTheMicAgain() {
        val r = Rigged()
        r.enter()
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        r.sim.advance(500)
        r.audio.feed(100)
        r.bridge.emit(BridgeEvent.State(r.bridge.nextSeq(), HostState.WAITING))
        r.sim.settle()
        assertFalse("応答待ちでマイクは止まる(半二重)", r.audio.recording)
        r.bridge.emit(BridgeEvent.Reply(r.bridge.nextSeq(), 0, "こんにちは", ReplyAudioInfo("wav", 24_000, 2000, 4200), null, true, "talking"))
        r.sim.settle()
        assertEquals(1, r.audio.startCount)
        assertFalse(r.audio.recording)
        assertEquals("こんにちは", r.ui.reply)
    }
}
