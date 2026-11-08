package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 本物の `VoiceSession`(フェイクの BridgeApi / AudioSource / ReplyPlayer、仮想時計)を [DaidaiVoice] に載せた通し。
 * 結線の約束(開く条件・止める条件・表示の連動)が、実際のセッションの順序のもとでも守られることを確かめる。
 */
class DaidaiVoiceSessionTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    // 自動切替の判定は AutoSwitchDiagnostics(プロセス全体の状態)に残る。ほかのテストへ持ち出さない
    @Before fun clearDiagnostics() = AutoSwitchDiagnostics.clear()
    @After fun clearDiagnosticsAfter() = AutoSwitchDiagnostics.clear()

    /** 本番の [AndroidVoiceRuntime] と同じ取消の順序(取消 → マイクを開く許可の取り消し + 停止)。スレッドの終了は [LateCreateAfterCloseTest] */
    private class Runtime(val session: VoiceSession, val audio: FakeAudio) : VoiceRuntime {
        override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
        override fun retry() = session.retry()
        override fun cancel() = session.cancel()
        override fun stopMicNow() = session.stopMicNow()
        override fun close() {
            session.cancel()
            session.stopMicNow()
        }
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
        var settings = configured
        val runtimes = mutableListOf<Runtime>()
        val voice = DaidaiVoice(
            pages, policy, { settings },
            VoiceRuntimeFactory { _, listener ->
                Runtime(VoiceSession(audio, bridge, player, sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0"), audio)
                    .also { runtimes += it }
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

        /**
         * B の確定のデバウンスのあと、セッションの作成の応答の配送(`onCreated`)だけがセッションのキューに積まれた状態まで進める
         * (作成の遅延 0。マイクはまだ開いていない)。このあとの取消が、キューの先にある作成成功より後になる競合を再現する。
         */
        fun holdCreatedInTheSessionQueue() {
            enter()
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS - 1)
            sim.tick(1)
            var guard = 0
            // 作成の通信が走り、完了して、その応答の配送(onCreated)がキューに積まれたところで止める(配送はまだ実行しない)
            while (bridge.created.isEmpty() || worker.inflightCount > 0) check(sim.step() && guard++ < 50) { "セッションの作成まで進まない" }
            assertEquals("まだマイクを開いていない", 0, audio.startCount)
        }

        fun assertMicNeverOpened() {
            sim.advance(10_000)
            assertEquals("マイクを一度も開いていない", 0, audio.startCount)
            assertFalse(audio.recording)
            assertEquals("開始音も鳴らさない", 0, tones)
            assertFalse(ui.micActive)
            assertFalse(pages.sessionActive)
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

    // ---- 取消とマイクの開始の競合(作成成功の処理がキューに積まれたまま、取消が来る)

    @Test fun leavingBWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rigged()
        r.holdCreatedInTheSessionQueue()
        r.leave() // 右スワイプ: cancel + stopMicNow(キューの先の作成成功より後に処理される)
        r.assertMicNeverOpened()
        assertEquals("作成が成功していたホストのセッションは消す", listOf("s-1"), r.bridge.cancels)
    }

    @Test fun goingToTheBackgroundWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rigged()
        r.holdCreatedInTheSessionQueue()
        r.voice.stop() // onPause: 取消 + runtime の破棄
        r.assertMicNeverOpened()
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test fun disposingTheOldRuntimeOnResumeWithChangedSettingsNeverOpensItsMic() {
        val r = Rigged()
        r.holdCreatedInTheSessionQueue()
        r.settings = configured.copy(url = "http://192.168.0.200:8800")
        r.voice.onResume() // 接続先が変わった: 古い runtime を破棄
        r.assertMicNeverOpened()
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertEquals(1, r.runtimes.size)
    }

    @Test fun rebuildingTheRuntimeForChangedSettingsNeverOpensTheOldOnesMicAndTheNewOneOpensOnce() {
        val r = Rigged()
        r.holdCreatedInTheSessionQueue()
        r.settings = configured.copy(url = "http://192.168.0.200:8800")
        r.voice.onResume() // cancel + 古い runtime の close
        r.sim.advance(10_000)
        assertEquals(0, r.audio.startCount)
        r.leave()
        r.enter() // 新しい設定で入り直す(新しい runtime)
        r.sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        assertEquals(2, r.runtimes.size)
        assertEquals("新しい runtime のマイクは 1 回だけ", 1, r.audio.startCount)
        assertTrue(r.audio.recording)
        assertEquals("古いセッションは消えている", listOf("s-1"), r.bridge.cancels)
        r.leave()
        assertFalse(r.audio.recording)
    }

    @Test fun anOldCreatedResultCannotReopenTheMicAfterTheUserLeft() {
        val r = Rigged()
        r.holdCreatedInTheSessionQueue()
        r.leave()
        r.sim.advance(30_000)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoiceState.IDLE, r.ui.voice)
    }

    // ---- AC19: 無発話のあとの自動マイクの抑止(DaidaiController → AutoSwitchPolicy → DaidaiVoice → 本物の VoiceSession)

    /** 通知の自動切替から本物の [VoiceSession] までを、本番と同じ結線でつないだもの(照度は明るい。時刻は [Sim] の仮想時間) */
    private inner class AutoRig {
        val log = mutableListOf<String>()
        val sim = Sim()
        val bridge = FakeBridge(sim, log)
        val worker = FakeWorker(sim, bridge).also { bridge.worker = it }
        val audio = FakeAudio(sim, log)
        val player = FakePlayer(log)
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()
        val controller = DaidaiController(pages, { configured }, { }, { }, { sim.now })
        val starts = mutableListOf<StartReason>()
        val voice = DaidaiVoice(
            pages, controller.policy, { configured },
            VoiceRuntimeFactory { _, listener ->
                val session = VoiceSession(audio, bridge, player, sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0")
                object : VoiceRuntime {
                    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) {
                        starts += reason
                        session.start(reason, noticeId, autoStarted)
                    }
                    override fun retry() = session.retry()
                    override fun cancel() = session.cancel()
                    override fun stopMicNow() = session.stopMicNow()
                    override fun close() {
                        session.cancel()
                        session.stopMicNow()
                    }
                }
            },
            sim, { rendered += it }, StartSound { }, { sim.now },
        )
        private var rev = 0L

        init {
            controller.entryListener = voice
            controller.start(PanelState(), "ep")
            controller.onLux(100f)
            feed("baseline") // 最初の status は基準(切り替えない)
        }

        private fun fixture() = javaClass.getResource("/fixtures/status-photo.json")!!.readText()

        fun feed(id: String) {
            val n = JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", 3)
            val d = JSONObject().put("rev", ++rev).put("notices", JSONArray().put(n))
            val status = KioskStatus.parse(JSONObject(fixture()).put("daidai", d).toString())
            controller.onKioskState(PanelState().withStatus(status, sim.now))
        }

        /** 通知を 1 件送る。自動切替のアニメーションが終わり、デバウンスが過ぎるまで進める */
        fun notify(id: String) {
            feed(id)
            sim.settle()
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }

        fun swipeToB() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.B, sim.now)
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }

        fun leave() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.A, sim.now)
            sim.settle()
            pages.animationEnded(sim.now)
            sim.settle()
        }

        val lastDenial get() = controller.policy.lastResult?.micDenial
    }

    @Test fun noSpeechAfterAnAutoStartBlocksTheNextAutoMicUntilAManualSessionEndsNormally() {
        // 契約: 本物の VoiceSession がホストの no_speech を onEnded の reason に載せる文字列が、ポリシーの REASON_NO_SPEECH と一致する
        assertEquals(AutoSwitchPolicy.REASON_NO_SPEECH, VoiceErrorCode.NO_SPEECH.code)

        val r = AutoRig()
        // 1. 自動入場(AUTO_NOTICE)でマイクが開く
        r.notify("n1")
        assertEquals(listOf(StartReason.NOTIFICATION), r.starts)
        assertEquals("自動入場でマイクが開く", 1, r.audio.startCount)
        assertTrue(r.audio.recording)
        // 2. ホストが no_speech を返し、VoiceSession が無発話で終わる
        r.bridge.emit(BridgeEvent.Error(r.bridge.nextSeq(), "no_speech", null))
        r.sim.settle()
        assertFalse("無発話でマイクは止まる", r.audio.recording)
        assertEquals(VoiceState.ERROR, r.rendered.last().voice)
        r.leave()

        // 3. クールダウン(5 分)が過ぎても、次の通知の自動入場ではマイクを開かない
        r.sim.advance(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 60_000)
        r.notify("n2")
        assertEquals("B には切り替わる", Page.B, r.pages.settledPage)
        assertEquals("クールダウンではなく無発話の抑止が理由", MicDenial.AFTER_NO_SPEECH, r.lastDenial)
        r.sim.advance(10_000)
        assertEquals("マイクは開かない", 1, r.audio.startCount)
        assertEquals("セッションも作らない", 1, r.bridge.created.size)
        assertFalse(r.audio.recording)
        r.leave()

        // 4. 利用者が手動(スワイプ)で入り、no_speech 以外で終えると抑止が解除される
        r.sim.advance(1_000)
        r.swipeToB()
        assertEquals(listOf(StartReason.NOTIFICATION, StartReason.SWIPE), r.starts)
        assertEquals("手動のセッションではマイクが開く", 2, r.audio.startCount)
        r.bridge.emit(BridgeEvent.Error(r.bridge.nextSeq(), "asr_failed", null))
        r.sim.settle()
        assertFalse(r.audio.recording)
        r.leave()

        // 5. 次の通知の自動入場では、再びマイクが開く
        r.sim.advance(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 60_000)
        r.notify("n3")
        assertNull("抑止は解除されている", r.lastDenial)
        assertEquals(listOf(StartReason.NOTIFICATION, StartReason.SWIPE, StartReason.NOTIFICATION), r.starts)
        assertEquals(3, r.audio.startCount)
        assertTrue(r.audio.recording)
        r.leave()
    }

    @Test fun aManualSessionEndingWithNoSpeechDoesNotReleaseTheHold() {
        val r = AutoRig()
        r.notify("n1")
        r.bridge.emit(BridgeEvent.Error(r.bridge.nextSeq(), "no_speech", null))
        r.sim.settle()
        r.leave()
        r.sim.advance(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 60_000)
        r.swipeToB()
        assertEquals(2, r.audio.startCount)
        r.bridge.emit(BridgeEvent.Error(r.bridge.nextSeq(), "no_speech", null)) // 手動でも無発話なら解除しない
        r.sim.settle()
        r.leave()
        r.sim.advance(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 60_000)
        r.notify("n2")
        assertEquals(MicDenial.AFTER_NO_SPEECH, r.lastDenial)
        assertEquals("マイクは開かない", 2, r.audio.startCount)
    }
}
