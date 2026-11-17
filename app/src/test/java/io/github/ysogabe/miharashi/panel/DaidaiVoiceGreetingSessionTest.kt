package io.github.ysogabe.miharashi.panel

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 段 G の通し: 本物の `VoiceSession` を [DaidaiVoice] に載せ、挨拶の置き場から取った WAV を、作成 → 挨拶(マイクは閉じたまま)→ 300 ms → マイク、
 * の順で鳴らす。挨拶が無ければチャイム。挨拶の途中で B を離れたら、録音は 0 回。
 */
class DaidaiVoiceGreetingSessionTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")
    private val jst = 9 * 3_600_000L
    private val evening = 1_794_841_200_000L + 19 * 3_600_000L // 2026-11-17 19:00 JST

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private fun wav(ms: Int) = StartChime.wrap(ShortArray(48_000 * ms / 1000) { 700 }, 48_000)

    private class Store(val wavs: Map<String, ByteArray>) : CueSource {
        override fun get(name: String) = wavs[name]
        override fun refreshIfDue() = Unit
        override fun close() = Unit
    }

    private inner class Rigged(cues: Map<String, ByteArray>) {
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
                val session = VoiceSession(audio, bridge, player, sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0")
                object : VoiceRuntime {
                    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = session.start(reason, noticeId, autoStarted)
                    override fun retry() = session.retry()
                    override fun start(request: TurnRequest) = session.start(request)
                    override fun retry(request: TurnRequest) = session.retry(request)
                    override fun endConversation(handle: String) = Unit
                    override fun cancel() = session.cancel()
                    override fun stopMicNow() = session.stopMicNow()
                    override fun close() { session.cancel(); session.stopMicNow() }
                }
            },
            sim, { rendered += it }, StartSound { tones++ }, { sim.now }, { GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT) },
            cueFactory = CueSourceFactory { Store(cues) }, wallClockMs = { evening }, tzOffsetMs = { jst },
        )
        val ui get() = rendered.last()

        init {
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

        fun leave() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.A, sim.now)
            sim.settle()
            pages.animationEnded(sim.now)
            sim.settle()
        }
    }

    @Test fun theEveningGreetingPlaysBeforeTheMicAndTheSecondSwipeIsShort() {
        val greet = wav(2_120)
        val plain = wav(1_480)
        val r = Rigged(mapOf("g_evening_swipe" to greet, "g_plain_swipe" to plain))
        r.enter()
        assertEquals(1, r.bridge.created.size)
        assertArrayEquals("19 時の最初のスワイプは「こんばんは。ご用ですか?」", greet, r.player.lastWav)
        assertEquals(0, r.audio.startCount)
        assertFalse(r.ui.micActive)
        assertEquals("挨拶の間はアバターが話す", DaidaiPose.TALKING, r.ui.avatarPose)
        assertTrue(r.pages.sessionActive)
        r.player.finish(true)
        r.sim.settle()
        r.sim.advance(VoiceLimits.CUE_ECHO_WAIT_MS - 1)
        assertEquals(0, r.audio.startCount)
        r.sim.advance(1)
        assertTrue(r.audio.recording)
        assertTrue(r.ui.micActive)
        assertEquals("挨拶のあとはビープを鳴らさない", 0, r.tones)
        r.leave()
        assertFalse(r.audio.recording)

        r.enter()
        assertArrayEquals("同じ夜の 2 回目は「何かご用ですか?」", plain, r.player.lastWav)
    }

    @Test fun withoutAGreetingTheChimePlaysInTheSameOrder() {
        val r = Rigged(emptyMap())
        r.enter()
        assertArrayEquals(StartChime.wav(), r.player.lastWav)
        assertEquals(0, r.audio.startCount)
        r.player.finish(true)
        r.sim.settle()
        r.sim.advance(VoiceLimits.CUE_ECHO_WAIT_MS)
        assertTrue(r.audio.recording)
        assertEquals("チャイムを鳴らせたらビープは鳴らさない", 0, r.tones)
    }

    @Test fun leavingDuringTheGreetingStopsItAndNeverRecords() {
        val r = Rigged(mapOf("g_evening_swipe" to wav(2_120)))
        r.enter()
        assertTrue(r.player.playing)
        r.leave()
        assertFalse(r.player.playing)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        r.sim.advance(10_000)
        assertEquals("録音は 0 回", 0, r.audio.startCount)
        assertEquals(0, r.tones)
        assertFalse(r.pages.sessionActive)
    }
}
