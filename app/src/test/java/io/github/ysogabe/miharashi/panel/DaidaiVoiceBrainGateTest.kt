package io.github.ysogabe.miharashi.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 段階 2b-2(R15)の通し: 本物の `VoiceSession` を [DaidaiVoice] に載せ、画面([DaidaiView])が表記を描き終えた確認が来ないとき、
 * 録音せずに閉じ、ホストの DELETE と会話の終わりが 1 回ずつ出ること。画面の偽は、確認を返す([drawnBrainAck])かどうかを試験が決める。
 */
class DaidaiVoiceBrainGateTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private inner class Rigged(brain: BrainInfo?, var screenDraws: Boolean) {
        val log = mutableListOf<String>()
        val sim = Sim()
        val bridge = FakeBridge(sim, log)
        val worker = FakeWorker(sim, bridge).also { bridge.worker = it }
        val audio = FakeAudio(sim, log)
        val player = FakePlayer(log)
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()

        /** runtime が受け取った確認 */
        val forwarded = mutableListOf<Int>()

        /** runtime が会話の終わりを頼まれたハンドル */
        val conversationEnds = mutableListOf<String>()
        var tones = 0
        val policy = AutoSwitchPolicy(AmbientLightGate(sample = { LuxSample(100f) }, config = { GateConfig.from(configured) }))
        val voice: DaidaiVoice = DaidaiVoice(
            pages, policy, { configured },
            VoiceRuntimeFactory { _, listener ->
                val session = VoiceSession(audio, bridge, CueRejectingPlayer(player), sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0")
                object : VoiceRuntime {
                    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
                    override fun retry() = session.retry()
                    override fun start(request: TurnRequest) = session.start(request)
                    override fun retry(request: TurnRequest) = session.retry(request)
                    override fun endConversation(handle: String) { conversationEnds += handle }
                    override fun brainNoticeDrawn(token: Int) { forwarded += token; session.brainNoticeDrawn(token) }
                    override fun cancel() = session.cancel()
                    override fun stopMicNow() = session.stopMicNow()
                    override fun close() { session.cancel(); session.stopMicNow() }
                }
            },
            sim,
            { rendered += it; it.drawnBrainAck().let { ack -> if (screenDraws && ack != 0) voice.onBrainNoticeDrawn(ack) } },
            StartSound { tones++ }, { sim.now }, { GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT) },
        )
        val ui get() = rendered.last()

        init {
            bridge.createReply = { n ->
                ok(SessionInfo("s-$n", bridge.hostLimits, conversation = ConversationInfo("h-$n", null, true), brain = brain))
            }
            pages.addListener(object : PageListener {
                override fun onSettled(page: Page, reason: PageReason) {
                    if (page == Page.B) voice.onEntered(DaidaiEntry(reason, false, openMic = true, noticeId = null, configured = true))
                    else voice.onLeft()
                }
            })
            voice.onResume()
        }

        fun enter() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.B, sim.now)
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }
    }

    private val cloud = BrainInfo(BrainName.DAIDAI, BrainEgress.CLOUD)

    @Test fun whenTheScreenNeverConfirmsTheTurnEndsWithoutRecordingAndEndsTheHostSessionAndTheConversationOnce() {
        val r = Rigged(cloud, screenDraws = false)
        r.enter()
        assertEquals(1, r.bridge.created.size)
        assertTrue("表記は出ている(確認だけが来ない)", r.ui.brainNotice != null)
        assertEquals(0, r.audio.startCount)
        r.sim.advance(VoiceLimits.BRAIN_NOTICE_DRAW_MS - 1)
        assertEquals(0, r.audio.startCount)
        assertFalse(r.ui.voice == VoiceState.ERROR)
        r.sim.advance(1)
        assertEquals(VoiceState.ERROR, r.ui.voice)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.ui.error)
        assertEquals("録音 0 回", 0, r.audio.startCount)
        assertEquals("チャンク 0 回", 0, r.bridge.chunks.size)
        assertEquals("ホストのセッションの DELETE は 1 回", listOf("s-1"), r.bridge.cancels.toList())
        assertEquals("会話の終わりは 1 回(ハンドルがあるとき)", listOf("h-1"), r.conversationEnds.toList())
        assertEquals("ERROR のあとは続きを作らない", 1, r.bridge.created.size)
        r.sim.advance(10_000)
        assertEquals(1, r.bridge.created.size)
        assertEquals(1, r.conversationEnds.size)
        assertEquals(1, r.bridge.cancels.size)
        assertFalse("会話が終わったので、B の無操作タイマーを止めない", r.pages.sessionActive)
    }

    @Test fun whenTheScreenConfirmsTheMicOpensAndOnlyTheCurrentTokenIsForwarded() {
        val r = Rigged(cloud, screenDraws = true)
        r.enter()
        assertTrue(r.ui.micActive)
        assertEquals(1, r.audio.startCount)
        // 画面は描くたびに確認を返す(重複はセッションが捨てる)。どれも、画面に載った今の往復の印
        val token = r.rendered.first { it.brainAck != 0 }.brainAck
        assertTrue(r.forwarded.isNotEmpty())
        assertTrue("転送した印は、画面に載った印だけ: ${r.forwarded}", r.forwarded.all { it == token })
    }

    @Test fun anAckOfAnOldTokenIsDroppedBeforeTheRuntime() {
        val r = Rigged(cloud, screenDraws = false)
        r.enter()
        val token = r.ui.brainAck
        assertNotEquals(0, token)
        r.voice.onBrainNoticeDrawn(token + 1000)
        r.voice.onBrainNoticeDrawn(0)
        assertTrue("今のスナップショットの印と違う確認は、runtime に渡さない", r.forwarded.isEmpty())
        assertEquals(0, r.audio.startCount)
        r.voice.onBrainNoticeDrawn(token)
        r.sim.settle()
        assertEquals(listOf(token), r.forwarded.toList())
        assertEquals(1, r.audio.startCount)
    }

    @Test fun anAckAfterLeavingIsNotForwardedAndNothingOpens() {
        val r = Rigged(cloud, screenDraws = false)
        r.enter()
        val token = r.ui.brainAck
        r.voice.cancel()
        r.sim.settle()
        r.voice.onBrainNoticeDrawn(token)
        r.sim.settle()
        assertEquals(0, r.audio.startCount)
        assertEquals("離れたあとの画面は印を持たない", 0, r.ui.brainAck)
    }

    @Test fun aHostBrainNeedsNoConfirmationAndTheScreenReturnsNothing() {
        val r = Rigged(BrainInfo(BrainName.OTHER, BrainEgress.HOST), screenDraws = false)
        r.enter()
        assertTrue(r.ui.micActive)
        assertEquals(1, r.audio.startCount)
        assertTrue(r.rendered.all { it.brainAck == 0 })
        assertTrue(r.forwarded.isEmpty())
    }

    @Test fun anOldHostWithoutABrainNeedsNoConfirmation() {
        val r = Rigged(null, screenDraws = false)
        r.enter()
        assertTrue(r.ui.micActive)
        assertEquals(1, r.audio.startCount)
        assertTrue(r.rendered.all { it.brainAck == 0 && it.brainNotice == null })
    }
}
