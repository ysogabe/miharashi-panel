package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 開始の合図の選び方と結線(段 G。設計書(段階 2)§5.8.1・§5.8.2・§5.8.4・§5.8.6)。挨拶の選択(場面 × 時間帯 × その時間帯で最初か)、
 * 挨拶の置き場の取り直し、自動切替の歯止めを数える位置、開始音(ビープ)を鳴らす条件。
 */
class DaidaiVoiceCueTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")
    private val jst = 9 * 3_600_000L
    private val midnightJst = 1_794_841_200_000L // 2026-11-17T00:00+09:00

    private class Runtime(val listener: VoiceSessionListener) : VoiceRuntime {
        val turns = mutableListOf<TurnRequest>()
        val retries = mutableListOf<TurnRequest>()
        override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = Unit
        override fun retry() = Unit
        override fun start(request: TurnRequest) { turns += request }
        override fun retry(request: TurnRequest) { retries += request }
        override fun endConversation(handle: String) = Unit
        override fun cancel() = Unit
        override fun stopMicNow() = Unit
        override fun close() = Unit
    }

    private class Cues : CueSource {
        val wavs = HashMap<String, ByteArray>()
        var refreshes = 0
        var closed = false
        override fun get(name: String) = wavs[name]
        override fun refreshIfDue() { refreshes++ }
        override fun close() { closed = true }
    }

    private inner class Harness(var settings: DaidaiSettings = configured) {
        val sim = Sim()
        val pages = PageController()
        val runtimes = mutableListOf<Runtime>()
        val cueSources = mutableListOf<Cues>()
        val rendered = mutableListOf<DaidaiUiState>()
        val entries = mutableListOf<DaidaiEntry>()
        var tones = 0
        var followTones = 0
        var wall = midnightJst + 8 * 3_600_000L // 8:00
        lateinit var voice: DaidaiVoice
        val controller = DaidaiController(pages, { settings }, { voice.onBaseUi(it) }, { }, { sim.now })
        val rt get() = runtimes.last()
        val cues get() = cueSources.last()

        init {
            voice = DaidaiVoice(
                pages, controller.policy, { settings },
                VoiceRuntimeFactory { _, l -> Runtime(l).also { runtimes += it } },
                sim, { rendered += it },
                object : StartSound {
                    override fun play() { tones++ }
                    override fun playFollow() { followTones++ }
                },
                { sim.now }, { GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT) },
                cueFactory = CueSourceFactory { Cues().also { c -> StartCue.NAMES.forEach { n -> c.wavs[n] = n.toByteArray() }; cueSources += c } },
                wallClockMs = { wall }, tzOffsetMs = { jst },
            )
            controller.entryListener = object : DaidaiEntryListener {
                override fun onEntered(entry: DaidaiEntry) { entries += entry; voice.onEntered(entry) }
                override fun onLeft() { voice.onLeft() }
            }
        }

        fun swipeIn() {
            controller.start(PanelState(), "ep")
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.B, sim.now)
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }

        fun swipeOut() {
            assertTrue(pages.beginDrag(sim.now))
            pages.endDrag(Page.A, sim.now)
            pages.animationEnded(sim.now)
            sim.settle()
        }

        /** 今の往復の合図を鳴らし始めた(セッションがそう通知した)と再現する */
        fun cueStarted(greeting: CueChoice? = rt.turns.last().cue?.choice) {
            val r = rt.turns.last()
            rt.listener.onCueStarted(r.autoStarted, r.tag, greeting)
            sim.settle()
        }

        fun micOpened(cue: MicCue, follow: Boolean = false) {
            val r = rt.turns.last()
            rt.listener.onMicOpened(r.autoStarted, follow, r.tag, cue)
            sim.settle()
        }

        fun endTurn(outcome: TurnOutcome, reason: String = "done") {
            val r = rt.turns.last()
            rt.listener.onEnded(r.autoStarted, reason, outcome, r.tag)
            sim.settle()
        }

        fun ready() {
            controller.start(PanelState(), "ep")
            controller.onLux(180f)
            feed(1)
        }

        fun feed(rev: Long, vararg ids: String) {
            val ns = ids.map { JSONObject().put("id", it).put("title", "t").put("body", "b-$it").put("age_s", 3).put("priority", "normal") }
            val d = JSONObject().put("notices", JSONArray(ns)).put("ack", true).put("unseen", ns.size).put("rev", rev)
            val fixture = javaClass.getResource("/fixtures/status-photo.json")!!.readText()
            controller.onKioskState(PanelState().withStatus(KioskStatus.parse(JSONObject(fixture).put("daidai", d).toString()), sim.now))
        }

        fun autoSwitch(rev: Long, id: String) {
            feed(rev, id)
            pages.animationEnded(sim.now)
            sim.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
        }
    }

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    @Test fun aSwipeGreetsWithTheDaypartFirstAndTheShortFormAfterwards() {
        val h = Harness()
        h.voice.onResume()
        assertEquals("最初の入場より前に取りに行く", 1, h.cues.refreshes)
        h.swipeIn()
        val plan = h.rt.turns.single().cue!!
        assertEquals("g_morning_swipe", plan.choice!!.name)
        assertArrayEquals("g_morning_swipe".toByteArray(), plan.greeting)
        assertTrue(WavValidator.validate(plan.chime).durationMs > 0)
        assertTrue("入場の前にも取り直しを頼む(待たない)", h.cues.refreshes >= 2)
        h.cueStarted()
        h.micOpened(MicCue.HEARD)
        assertEquals("挨拶を鳴らし終えたらビープは鳴らさない", 0, h.tones)
        h.endTurn(TurnOutcome.ERROR, "no_speech")
        h.swipeOut()

        h.swipeIn()
        assertEquals("同じ時間帯の 2 回目は短い形", "g_plain_swipe", h.rt.turns.last().cue!!.choice!!.name)
    }

    @Test fun theDaypartIsConsumedOnlyWhenTheGreetingActuallyStarts() {
        val h = Harness()
        h.swipeIn()
        assertEquals("g_morning_swipe", h.rt.turns.last().cue!!.choice!!.name)
        h.cueStarted(greeting = null) // 挨拶を鳴らせずチャイムから始めた
        h.micOpened(MicCue.HEARD)
        h.endTurn(TurnOutcome.ERROR, "no_speech")
        h.swipeOut()
        h.swipeIn()
        assertEquals("チャイムに戻った回は時間帯を使わない", "g_morning_swipe", h.rt.turns.last().cue!!.choice!!.name)
    }

    @Test fun aNotificationUsesTheNoticePhraseAndSharesTheDaypartWithSwipes() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        val plan = h.rt.turns.single().cue!!
        assertEquals("g_morning_notice", plan.choice!!.name)
        assertEquals(StartReason.NOTIFICATION, h.rt.turns.single().reason)
        h.cueStarted()
        h.micOpened(MicCue.HEARD)
        h.endTurn(TurnOutcome.PLAYED)
        h.swipeOut()
        h.sim.advance(60_000)
        h.swipeIn()
        assertEquals("通知で挨拶した時間帯は、スワイプも短い形", "g_plain_swipe", h.rt.turns.last().cue!!.choice!!.name)
        h.wall += 3 * 3_600_000L // 11:00
        h.swipeOut()
        h.swipeIn()
        assertEquals("g_day_swipe", h.rt.turns.last().cue!!.choice!!.name)
    }

    @Test fun aRetryUsesTheShortVoiceEvenAfterANotification() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.rt.listener.onSnapshot(VoiceSnapshot(state = VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.sim.settle()
        h.voice.onErrorTapped()
        val r = h.rt.retries.single()
        assertEquals("ホストへの reason は前の会話のまま", StartReason.NOTIFICATION, r.reason)
        assertEquals("g_retry", r.cue!!.choice!!.name)
        assertArrayEquals("g_retry".toByteArray(), r.cue!!.greeting)
        assertNull(r.cue!!.choice!!.daypartKey)
    }

    @Test fun followTurnsCarryNoCueAndKeepTheirTone() {
        val h = Harness()
        h.swipeIn()
        h.cueStarted()
        h.micOpened(MicCue.HEARD)
        h.endTurn(TurnOutcome.PLAYED)
        h.sim.advance(DaidaiVoice.ECHO_WAIT_MS)
        val follow = h.rt.turns.last()
        assertTrue(follow.follow)
        assertNull("続きの往復には挨拶を付けない", follow.cue)
        h.rt.listener.onMicOpened(false, true, follow.tag, MicCue.NONE)
        h.sim.settle()
        assertEquals(1, h.followTones)
        assertEquals(0, h.tones)
    }

    @Test fun theBeepSoundsOnlyWhenNoCueSoundedOrThereWasNoCueStage() {
        val h = Harness()
        h.swipeIn()
        h.cueStarted()
        h.micOpened(MicCue.FAILED)
        assertEquals("挨拶もチャイムも鳴らせなかった: 最後の手段のビープ", 1, h.tones)
        h.micOpened(MicCue.NONE)
        assertEquals("合図の段が無い: 今までどおり", 2, h.tones)
        h.micOpened(MicCue.HEARD)
        assertEquals(2, h.tones)
    }

    @Test fun anAutoStartIsCountedAtTheCueStartEvenIfCancelledBeforeTheMic() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        assertTrue(h.entries.last().openMic)
        h.cueStarted()
        // 挨拶の途中で取り消しても、端末が喋り始めたことは数える: 次の自動切替はクールダウンで止まる
        h.endTurn(TurnOutcome.CANCELLED, "cancelled")
        h.swipeOut()
        h.sim.advance(60_000)
        h.autoSwitch(3, "n2")
        assertFalse(h.entries.last().openMic)
        assertEquals(MicDenial.COOLDOWN, h.entries.last().micDenial)
    }

    @Test fun theMicOpenAfterACueDoesNotCountTheAutoStartAgain() {
        val h = Harness()
        h.ready()
        // 1 時間に 6 回まで。合図とマイクの両方で数えると 3 回目の後に止まる
        for (i in 1..6) {
            h.autoSwitch(1L + i, "n$i")
            assertTrue("$i 回目は開く", h.entries.last().openMic)
            h.cueStarted()
            h.micOpened(MicCue.HEARD)
            h.endTurn(TurnOutcome.ERROR, "done")
            h.swipeOut()
            h.sim.advance(AutoSwitchPolicy.AUTO_MIC_COOLDOWN_MS + 1_000)
        }
        h.autoSwitch(10, "n7")
        assertFalse("7 回目は 1 時間の上限で止まる", h.entries.last().openMic)
    }

    @Test fun aSwipeCueStartIsNotAnAutoStart() {
        val h = Harness()
        h.swipeIn()
        h.cueStarted()
        h.micOpened(MicCue.HEARD)
        h.endTurn(TurnOutcome.ERROR, "done")
        h.swipeOut()
        h.ready()
        h.autoSwitch(2, "n1")
        assertTrue("スワイプの合図は自動起動に数えない", h.entries.last().openMic)
    }

    @Test fun theCuePlayingSnapshotMakesTheAvatarTalkWithoutTheMicIndicator() {
        val h = Harness()
        h.swipeIn()
        h.rt.listener.onSnapshot(VoiceSnapshot(state = VoiceState.IDLE, active = true, cuePlaying = true))
        h.sim.settle()
        val ui = h.rendered.last()
        assertTrue(ui.cuePlaying)
        assertFalse(ui.micActive)
        assertEquals(DaidaiPose.TALKING, ui.avatarPose)
    }

    @Test fun cuesAreKeptAcrossPauseAndReplacedWhenTheEndpointChanges() {
        val h = Harness()
        h.voice.onResume()
        val first = h.cues
        h.voice.stop() // onPause: runtime は捨てるが挨拶は残す
        h.voice.onResume()
        assertSame(first, h.cues)
        assertFalse(first.closed)
        h.settings = configured.copy(token = "another-token-0123456789")
        h.voice.onResume()
        assertTrue("接続先が変わったら捨てる", first.closed)
        assertEquals(2, h.cueSources.size)
        h.settings = DaidaiSettings()
        h.voice.onResume()
        assertTrue("未設定なら捨てる", h.cues.closed)
    }

    @Test fun withoutCuesTheGreetingIsNullAndTheChimeIsStillThere() {
        val h = Harness()
        h.swipeIn()
        h.cues.wavs.clear()
        h.endTurn(TurnOutcome.CANCELLED, "cancelled")
        h.swipeOut()
        h.swipeIn()
        val plan = h.rt.turns.last().cue!!
        assertNull(plan.greeting)
        assertTrue(WavValidator.validate(plan.chime).durationMs > 0)
    }
}
