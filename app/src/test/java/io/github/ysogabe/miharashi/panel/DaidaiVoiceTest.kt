package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 呼び出しを記録するだけの [VoiceRuntime]。セッションのスレッドからの通知は [listener] を直接呼んで再現する。 */
private class FakeRuntime(val settings: DaidaiSettings, val listener: VoiceSessionListener) : VoiceRuntime {
    class Start(val reason: StartReason, val noticeId: String?, val auto: Boolean)

    val starts = mutableListOf<Start>()
    var retries = 0
    var cancels = 0
    var stopMics = 0
    var closed = false
    val log = mutableListOf<String>()

    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) {
        log += "start"; starts += Start(reason, noticeId, autoStarted)
    }

    override fun retry() { log += "retry"; retries++ }
    override fun cancel() { log += "cancel"; cancels++ }
    override fun stopMicNow() { log += "stopMic"; stopMics++ }
    override fun close() { log += "close"; closed = true }
}

/**
 * B の確定とマイクの結線(AC4, AC14, AC19、設計書 §4.3, §7)。純 JVM: 仮想時計 [Sim] がメインでもあり、
 * 実物の `PageController` / `DaidaiController` / `AutoSwitchPolicy` を使う。セッション一式は [FakeRuntime]。
 * 本物の `VoiceSession` を載せた通しは `DaidaiVoiceSessionTest`。
 */
class DaidaiVoiceTest {
    private val configured = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    private inner class Harness(var settings: DaidaiSettings = configured) {
        val sim = Sim()
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()
        val runtimes = mutableListOf<FakeRuntime>()
        val entered = mutableListOf<DaidaiEntry>()
        var tones = 0
        var toneThrows = false
        var factoryCalls = 0
        var failFactory = false
        lateinit var voice: DaidaiVoice
        val controller: DaidaiController = DaidaiController(pages, { settings }, { voice.onBaseUi(it) }, { }, { sim.now })
        val rt get() = runtimes.last()
        val ui get() = rendered.last()

        init {
            voice = DaidaiVoice(
                pages, controller.policy, { settings },
                VoiceRuntimeFactory { s, l ->
                    factoryCalls++
                    if (failFactory) throw IllegalStateException("no")
                    FakeRuntime(s, l).also { runtimes += it }
                },
                sim, { rendered += it }, StartSound { tones++; if (toneThrows) throw IllegalStateException("tone") }, { sim.now },
            )
            controller.entryListener = object : DaidaiEntryListener {
                override fun onEntered(entry: DaidaiEntry) { entered += entry; voice.onEntered(entry) }
                override fun onLeft() { voice.onLeft() }
            }
        }

        fun start() = controller.start(PanelState(), "ep")

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

        fun snapshot(s: VoiceSnapshot) {
            rt.listener.onSnapshot(s)
            sim.settle()
        }

        fun advance(ms: Long) = sim.advance(ms)

        fun feed(status: KioskStatus) = controller.onKioskState(PanelState().withStatus(status, sim.now))

        /** 明るい環境で基準を取る */
        fun ready() {
            start()
            controller.onLux(180f)
            feed(status(1))
        }

        /** 通知で自動切替して B に確定する */
        fun autoSwitch(rev: Long, id: String) {
            feed(status(rev, notice(id)))
            pages.animationEnded(sim.now)
        }

        /** 自動起動のセッションを 1 回まわす(切替 → 開始 → マイクが開いた → 終了 → A へ戻る) */
        fun autoCycle(rev: Long, id: String, endReason: String = "done") {
            autoSwitch(rev, id)
            advance(DaidaiVoice.ENTRY_DEBOUNCE_MS)
            rt.listener.onMicOpened(true)
            sim.settle()
            rt.listener.onEnded(true, endReason)
            sim.settle()
            swipeToA()
        }
    }

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun notice(id: String) =
        JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", 3).put("priority", "normal")

    private fun status(rev: Long, vararg ns: JSONObject): KioskStatus {
        val d = JSONObject().put("notices", JSONArray(ns.toList())).put("ack", true).put("unseen", ns.size).put("rev", rev)
        return KioskStatus.parse(JSONObject(fixture("status-photo.json")).put("daidai", d).toString())
    }

    private fun snap(
        state: VoiceState = VoiceState.IDLE, active: Boolean = false, mic: Boolean = false, error: VoiceErrorCode? = null,
        transcript: String = "", reply: String = "", pose: String? = null,
    ) = VoiceSnapshot(state = state, transcript = transcript, reply = reply, error = error, micActive = mic, noticePose = pose, active = active)

    // ---- スワイプ入場

    @Test fun swipeEntryStartsTheSessionOnlyAfterTheDebounce() {
        val h = Harness()
        h.start()
        h.swipeToB()
        assertEquals("確定の直後はまだ開かない", 0, h.factoryCalls)
        h.advance(DaidaiVoice.ENTRY_DEBOUNCE_MS - 1)
        assertEquals(0, h.factoryCalls)
        h.advance(1)
        val s = h.rt.starts.single()
        assertEquals(StartReason.SWIPE, s.reason)
        assertNull(s.noticeId)
        assertFalse("スワイプは自動起動ではない", s.auto)
    }

    @Test fun swipeEntryOpensTheMicRegardlessOfAutoListen() {
        val h = Harness(configured.copy(autoListen = false))
        h.start()
        h.swipeToB()
        h.advance(300)
        assertEquals("利用者の操作は auto_listen と無関係に開く", 1, h.rt.starts.size)
    }

    @Test fun draggingHalfwayNeverStartsTheSession() {
        val h = Harness()
        h.start()
        assertTrue(h.pages.beginDrag(h.sim.now)) // B が半分見えている
        h.advance(2_000)
        assertEquals(0, h.factoryCalls)
        h.pages.endDrag(Page.A, h.sim.now) // やめて A へ戻る
        h.pages.animationEnded(h.sim.now)
        h.advance(2_000)
        assertEquals(0, h.factoryCalls)
    }

    @Test fun returningToABeforeTheDebounceEndsNeverStarts() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.advance(100)
        h.swipeToA()
        h.advance(2_000)
        assertEquals("高速な往復で開かない", 0, h.factoryCalls)
    }

    @Test fun backAndForthFastStartsExactlyOneSession() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.advance(100)
        h.swipeToA()
        h.advance(100)
        h.swipeToB()
        h.advance(299)
        assertEquals(0, h.factoryCalls)
        h.advance(1)
        assertEquals(1, h.rt.starts.size)
    }

    @Test fun aDragInProgressWhenTheDebounceFiresDefersUntilTheGestureSettles() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.advance(100)
        assertTrue(h.pages.beginDrag(h.sim.now)) // B から A へ引き始めた
        h.advance(500)
        assertEquals("操作中は開かない", 0, h.factoryCalls)
        h.pages.endDrag(Page.B, h.sim.now) // 戻して B に留まる
        h.pages.animationEnded(h.sim.now)
        assertEquals("落ち着いた直後はまだ(もう一度デバウンス)", 0, h.factoryCalls)
        h.advance(300)
        assertEquals(1, h.rt.starts.size)
    }

    @Test fun nothingStartsWhenNotConfiguredAndNoRuntimeIsCreated() {
        val h = Harness(DaidaiSettings())
        h.start()
        h.swipeToB()
        h.advance(5_000)
        assertEquals("未設定ではセッション(とそのスレッド)を作らない", 0, h.factoryCalls)
        assertEquals(SetupGuide.NOT_CONFIGURED, h.ui.setup)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun tokenOnlyOrUrlOnlyIsNotConfigured() {
        for (s in listOf(DaidaiSettings(url = "http://h:8800"), DaidaiSettings(token = "bridge-token-0123456789"))) {
            val h = Harness(s)
            h.start()
            h.swipeToB()
            h.advance(2_000)
            assertEquals(0, h.factoryCalls)
        }
    }

    @Test fun settingsClearedBetweenEntryAndStartDoNotOpen() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.settings = DaidaiSettings() // 待っている間に設定が消えた
        h.advance(300)
        assertEquals(0, h.factoryCalls)
    }

    // ---- 自動切替(AC19)

    @Test fun autoNoticeOpensTheMicWithTheNoticeIdAndCountsTheAutoStart() {
        val h = Harness()
        h.ready()
        h.advance(1_000)
        h.autoSwitch(2, "n1")
        h.advance(300)
        val s = h.rt.starts.single()
        assertEquals(StartReason.NOTIFICATION, s.reason)
        assertEquals("n1", s.noticeId)
        assertTrue(s.auto)
        // 実際に開いた(onMicOpened)ときに数える: 開く前には数えない
        h.rt.listener.onEnded(true, "cancelled") // 開かないまま終わった
        h.sim.settle()
        h.swipeToA()
        h.advance(10_000)
        h.autoSwitch(3, "n2")
        assertTrue("マイクが開いていなければクールダウンに数えない", h.entered.last().openMic)
    }

    @Test fun autoMicStartIsRecordedWhenTheMicOpensAndBlocksTheNextOneForTheCooldown() {
        val h = Harness()
        h.ready()
        h.autoCycle(2, "n1")
        h.advance(60_000) // 1 分後
        h.autoSwitch(3, "n2")
        val e = h.entered.last()
        assertFalse(e.openMic)
        assertEquals(MicDenial.COOLDOWN, e.micDenial)
        h.advance(300)
        assertEquals("歯止めで開かない入場はセッションを始めない", 1, h.runtimes.single().starts.size)
    }

    @Test fun autoNoticeWithAutoListenOffDoesNotOpenTheMic() {
        val h = Harness(configured.copy(autoListen = false))
        h.ready()
        h.autoSwitch(2, "n1")
        assertFalse(h.entered.single().openMic)
        h.advance(5_000)
        assertEquals("セッションも作らない(カードは出る)", 0, h.factoryCalls)
        assertEquals("n1", h.ui.notice?.id)
    }

    @Test fun noSpeechAfterAnAutoStartBlocksTheNextAutoStart() {
        val h = Harness()
        h.ready()
        h.autoCycle(2, "n1", endReason = "no_speech") // AC19: onEnded(autoStarted=true, "no_speech")
        h.advance(6 * 60_000L) // クールダウンは過ぎた
        h.autoSwitch(3, "n2")
        val e = h.entered.last()
        assertFalse(e.openMic)
        assertEquals(MicDenial.AFTER_NO_SPEECH, e.micDenial)
        h.advance(300)
        assertEquals(1, h.runtimes.single().starts.size)
    }

    @Test fun aManualSessionEndingNormallyReleasesTheNoSpeechHold() {
        val h = Harness()
        h.ready()
        h.autoCycle(2, "n1", endReason = "no_speech")
        h.advance(6 * 60_000L)
        h.swipeToB() // 利用者の手動
        h.advance(300)
        h.rt.listener.onEnded(false, "done")
        h.sim.settle()
        h.swipeToA()
        h.advance(6 * 60_000L)
        h.autoSwitch(3, "n2")
        assertTrue(h.entered.last().openMic)
    }

    @Test fun sevenAutoStartsWithinAnHourAreRefusedAfterSix() {
        val h = Harness()
        h.ready()
        for (i in 1..6) {
            h.autoCycle(1L + i, "n$i")
            h.advance(5 * 60_000L + 10_000)
        }
        h.autoSwitch(8, "n7")
        val e = h.entered.last()
        assertFalse(e.openMic)
        assertEquals(MicDenial.HOURLY_LIMIT, e.micDenial)
        h.advance(300)
        assertEquals(6, h.runtimes.single().starts.size)
    }

    @Test fun sessionEndsAreForwardedToThePolicyWithTheAutoFlagAndReason() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        h.rt.listener.onEnded(true, "no_speech")
        h.sim.settle()
        h.swipeToA()
        h.advance(6 * 60_000L)
        h.autoSwitch(3, "n2")
        assertEquals(MicDenial.AFTER_NO_SPEECH, h.entered.last().micDenial)
    }

    // ---- 離脱で必ず止める

    private fun Harness.startSwipeSession() {
        start()
        swipeToB()
        advance(300)
        assertEquals(1, rt.starts.size)
    }

    @Test fun leavingBBySwipeCancelsAndStopsTheMicAtOnce() {
        val h = Harness()
        h.startSwipeSession()
        val cancels = h.rt.cancels
        val stops = h.rt.stopMics
        assertTrue(h.pages.beginDrag(h.sim.now))
        h.pages.endDrag(Page.A, h.sim.now) // 指を離した時点(確定を待たない)
        assertTrue(h.rt.cancels > cancels)
        assertTrue(h.rt.stopMics > stops)
    }

    @Test fun idleReturnCancels() {
        val h = Harness()
        h.startSwipeSession()
        val before = h.rt.cancels
        assertTrue(h.pages.goTo(Page.A, PageReason.IDLE_RETURN, h.sim.now))
        assertTrue(h.rt.cancels > before)
    }

    @Test fun onPauseStopsTheSessionAndDisposesTheRuntime() {
        val h = Harness()
        h.startSwipeSession()
        h.voice.stop()
        assertTrue(h.rt.cancels >= 1)
        assertTrue(h.rt.stopMics >= 1)
        assertTrue("スレッドなどを片づける", h.rt.closed)
        h.pages.onPause(h.sim.now)
        assertEquals(Page.A, h.pages.settledPage)
    }

    @Test fun pageControllerOnPauseAloneAlsoCancels() {
        val h = Harness()
        h.startSwipeSession()
        val before = h.rt.cancels
        h.pages.onPause(h.sim.now) // B にいたまま背面へ
        assertTrue(h.rt.cancels > before)
        assertTrue(h.rt.stopMics >= 1)
    }

    @Test fun openingSettingsSleepAndMonitorOffCancel() {
        val h = Harness()
        h.startSwipeSession()
        val before = h.rt.cancels
        h.voice.cancel() // PanelActivity: 設定画面・onSleepChanged(true)・モニター消灯は、すべてこれを呼ぶ
        assertEquals(before + 1, h.rt.cancels)
        assertTrue(h.rt.stopMics >= 1)
    }

    @Test fun cancelDuringTheDebounceCancelsTheStart() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.advance(100)
        h.voice.cancel()
        h.advance(1_000)
        assertEquals("開始待ちを捨てる", 0, h.factoryCalls)
    }

    @Test fun onResumeNeverStartsASession() {
        val h = Harness()
        h.startSwipeSession()
        h.voice.stop()
        h.start()
        h.voice.onResume()
        h.advance(5_000)
        assertEquals(1, h.runtimes.size)
        assertEquals("常に A から始まり、再開でも新しいセッションは始まらない", 1, h.rt.starts.size)
    }

    @Test fun aSnapshotWithTheMicOpenWhileBIsNotVisibleCancelsIt() {
        val h = Harness()
        h.startSwipeSession()
        h.swipeToA()
        val before = h.rt.cancels
        h.snapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        assertTrue("B が見えていないのにマイクが開いている通知は、確実に止める", h.rt.cancels > before)
    }

    // ---- 表示と sessionActive

    @Test fun sessionActiveIsTrueOnlyWhileTheSessionRuns() {
        val h = Harness()
        h.startSwipeSession()
        assertFalse(h.pages.sessionActive)
        h.snapshot(snap(VoiceState.IDLE, active = true)) // PREPARING は IDLE 表示に畳まれるが進行中
        assertTrue(h.pages.sessionActive)
        h.snapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        assertTrue(h.pages.sessionActive)
        h.snapshot(snap(VoiceState.REPLYING, active = true))
        assertTrue(h.pages.sessionActive)
        h.snapshot(snap(VoiceState.IDLE, active = false, reply = "こんにちは")) // DONE
        assertFalse(h.pages.sessionActive)
        h.snapshot(snap(VoiceState.ERROR, active = false, error = VoiceErrorCode.NO_SPEECH))
        assertFalse(h.pages.sessionActive)
    }

    @Test fun sessionActiveHoldsTheBIdleTimerAndReleasesItAfterwards() {
        val h = Harness(configured)
        h.pages.idleMs = 10_000
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        var t = h.sim.now
        repeat(30) { t += 1_000; assertFalse(h.pages.tick(t)) }
        h.snapshot(snap(VoiceState.IDLE, active = false))
        t += 1_000; assertFalse("終わった後の最初の呼び出しから数え直す", h.pages.tick(t))
        t += 10_000
        assertTrue(h.pages.tick(t))
    }

    @Test fun micIndicatorFollowsTheActualRecordingState() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.IDLE, active = true, mic = false))
        assertFalse(h.ui.micActive)
        h.snapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        assertTrue(h.ui.micActive)
        assertEquals(VoiceState.LISTENING, h.ui.voice)
        h.snapshot(snap(VoiceState.WAITING, active = true, mic = false))
        assertFalse("録音が止まれば表示も消える", h.ui.micActive)
    }

    @Test fun snapshotsAreMergedWithTheNoticeCardAndTheGuide() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("n1")))
        h.pages.animationEnded(h.sim.now)
        h.advance(300)
        h.snapshot(snap(VoiceState.REPLYING, active = true, transcript = "今日の予定は", reply = "会議が三件あります"))
        assertEquals("n1", h.ui.notice?.id)
        assertEquals("今日の予定は", h.ui.transcript)
        assertEquals("会議が三件あります", h.ui.reply)
        assertEquals(VoiceState.REPLYING, h.ui.voice)
        // 通知カードの更新で音声の状態が消えない
        h.feed(status(3, notice("n2"), notice("n1")))
        assertEquals(VoiceState.REPLYING, h.ui.voice)
        assertEquals("n2", h.ui.notice?.id)
    }

    @Test fun micPermissionErrorShowsTheGuide() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.MIC_PERMISSION))
        assertEquals(SetupGuide.MIC_PERMISSION, h.ui.guide)
    }

    @Test fun leavingClearsTheRenderedConversationOnDispose() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.REPLYING, active = true, transcript = "x", reply = "y"))
        h.voice.stop()
        assertEquals("", h.ui.reply)
        assertEquals("", h.ui.transcript)
        assertFalse(h.ui.micActive)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun staleRuntimeSnapshotsAreIgnoredAfterDispose() {
        val h = Harness()
        h.startSwipeSession()
        val old = h.rt
        h.voice.stop()
        old.listener.onSnapshot(snap(VoiceState.LISTENING, active = true, mic = true))
        h.sim.settle()
        assertFalse(h.ui.micActive)
        assertFalse(h.pages.sessionActive)
    }

    // ---- 再試行(利用者のタップだけ)

    @Test fun tapOnErrorRetriesAsAUserAction() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.voice.onErrorTapped()
        assertEquals(1, h.rt.retries)
        assertEquals("再試行は新しい start ではなく retry(autoStarted=false)", 1, h.rt.starts.size)
    }

    @Test fun tapWhenNotInErrorIsIgnored() {
        val h = Harness()
        h.startSwipeSession()
        for (st in listOf(snap(VoiceState.IDLE), snap(VoiceState.LISTENING, active = true, mic = true), snap(VoiceState.WAITING, active = true))) {
            h.snapshot(st)
            h.voice.onErrorTapped()
        }
        assertEquals(0, h.rt.retries)
    }

    @Test fun errorNeverRetriesByItself() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH))
        h.advance(10 * 60_000L)
        assertEquals(0, h.rt.retries)
        assertEquals(1, h.rt.starts.size)
    }

    @Test fun anErrorFromAnAutoStartedSessionIsNotRetriedAutomaticallyEither() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH))
        h.rt.listener.onEnded(true, "no_speech")
        h.advance(60_000)
        assertEquals(0, h.rt.retries)
        assertEquals(1, h.rt.starts.size)
    }

    @Test fun aRetryAfterAnAutoStartedSessionIsNotCountedAsAnAutoMicStart() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        h.rt.listener.onMicOpened(true) // 自動起動としてはここだけ数える
        h.sim.settle()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.rt.listener.onEnded(true, "host_unreachable")
        h.advance(4 * 60_000L)
        h.voice.onErrorTapped() // 利用者のタップ(autoStarted=false)
        h.rt.listener.onMicOpened(false)
        h.sim.settle()
        h.swipeToA()
        h.advance(60_000L + 2_000) // 最初の起動から 5 分を過ぎた
        h.autoSwitch(3, "n2")
        assertTrue("再試行のマイク起動は自動の歯止めに数えない", h.entered.last().openMic)
    }

    // ---- 自動マイク起動の記録は、マイクを開いたセッションの値で行う(配送の遅れ・別のセッションに左右されない)

    @Test fun anAutoMicStartIsRecordedEvenIfAManualSessionTakesOverBeforeTheNoticeIsDelivered() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
        h.rt.listener.onMicOpened(true) // 開いた(自動)。main への配送はまだ
        h.voice.onErrorTapped() // 配送の前に、利用者のタップで手動のセッションへ切り替わる
        h.sim.settle()
        h.swipeToA()
        h.advance(60_000) // 1 分後
        h.autoSwitch(3, "n2")
        val e = h.entered.last()
        assertFalse("実際に開いた自動起動は、あとで手動に切り替わっても歯止めに数える", e.openMic)
        assertEquals(MicDenial.COOLDOWN, e.micDenial)
    }

    @Test fun aManualMicStartIsNotCountedAsAutoEvenIfAnAutoSessionIsRunningInTheNewRuntime() {
        val h = Harness()
        h.ready()
        h.swipeToB() // 手動(スワイプ)
        h.advance(300)
        val old = h.rt
        h.swipeToA()
        h.voice.stop() // 作り直し(古い runtime は破棄済み)
        h.advance(10_000)
        h.autoSwitch(2, "n1") // 新しい runtime で自動のセッションが始まる
        h.advance(300)
        assertTrue(h.rt !== old)
        assertTrue(h.rt.starts.single().auto)
        old.listener.onMicOpened(false) // 古い runtime で開いていた手動のマイクの通知が、いま届く
        h.sim.settle()
        h.swipeToA()
        h.advance(60_000)
        h.autoSwitch(3, "n2")
        assertTrue("手動で開いたマイクを自動として数えない(新しい自動セッションはまだ開いていない)", h.entered.last().openMic)
    }

    @Test fun theTimeOfTheAutoMicStartIsWhenItOpenedNotWhenTheMainThreadGotTheNotice() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        h.rt.listener.onMicOpened(true) // 開いた時刻 T。main への配送は 2 分遅れる
        h.sim.tick(2 * 60_000L)
        h.sim.settle()
        h.swipeToA()
        h.advance(3 * 60_000L + 1_000L) // T から 5 分と 1 秒(配送の時刻からは 3 分と 1 秒)
        h.autoSwitch(3, "n2")
        assertTrue("クールダウンは開いた時刻から数える", h.entered.last().openMic)
    }

    @Test fun anAutoMicOpenedByAnOldDisposedRuntimeIsStillRecordedButDoesNotTouchTheScreen() {
        val h = Harness()
        h.ready()
        h.autoSwitch(2, "n1")
        h.advance(300)
        val old = h.rt
        h.voice.stop() // 背面: runtime を破棄
        val rendered = h.rendered.size
        old.listener.onSnapshot(snap(VoiceState.LISTENING, active = true, mic = true, transcript = "旧"))
        old.listener.onMicOpened(true) // 破棄の前に実際に開いていたマイク
        h.sim.settle()
        assertEquals("古い runtime のスナップショットは画面に出さない", rendered, h.rendered.size)
        assertFalse(h.pages.sessionActive)
        h.swipeToA()
        h.advance(60_000)
        h.autoSwitch(3, "n2")
        val e = h.entered.last()
        assertFalse("古い runtime で開いたマイクも歯止めに数える", e.openMic)
        assertEquals(MicDenial.COOLDOWN, e.micDenial)
    }

    @Test fun tapAfterLeavingBDoesNothing() {
        val h = Harness()
        h.startSwipeSession()
        h.snapshot(snap(VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH))
        h.swipeToA()
        h.voice.onErrorTapped()
        assertEquals(0, h.rt.retries)
    }

    // ---- 設定の変更・開始音

    @Test fun changingTheBridgeSettingsRebuildsTheRuntimeOnResume() {
        val h = Harness()
        h.startSwipeSession()
        val first = h.rt
        h.voice.onResume() // 同じ設定: 作り直さない(ただし取り消す)
        assertFalse(first.closed)
        h.settings = configured.copy(url = "http://192.168.0.200:8800")
        h.voice.onResume()
        assertTrue("設定が変わったら古いものを片づける", first.closed)
        h.swipeToA()
        h.swipeToB()
        h.advance(300)
        assertEquals(2, h.factoryCalls)
        assertEquals("http://192.168.0.200:8800", h.rt.settings.url)
    }

    @Test fun changingTheTokenAloneRebuildsToo() {
        val h = Harness()
        h.startSwipeSession()
        h.swipeToA()
        h.voice.stop()
        h.settings = configured.copy(token = "another-token-0123456789")
        h.start()
        h.swipeToB()
        h.advance(300)
        assertEquals("another-token-0123456789", h.rt.settings.token)
        assertEquals(2, h.factoryCalls)
    }

    @Test fun theSameRuntimeIsReusedAcrossEntriesWithSameSettings() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.advance(300)
        h.swipeToA()
        h.swipeToB()
        h.advance(300)
        assertEquals(1, h.factoryCalls)
        assertEquals(2, h.rt.starts.size)
    }

    @Test fun theStartToneNeverThrowsIntoTheSession() {
        val h = Harness()
        h.startSwipeSession()
        h.toneThrows = true
        h.rt.listener.onMicOpened(false) // 例外を投げたらセッションが終わってしまう
        assertEquals(1, h.tones)
    }

    @Test fun theStartToneIsPlayedOnTheCallerThreadWhenTheMicOpens() {
        val h = Harness()
        h.startSwipeSession()
        h.rt.listener.onMicOpened(false)
        assertEquals("セッションのスレッドで即座に鳴らす(メインへの post を待たない)", 1, h.tones)
    }

    @Test fun aFailingRuntimeFactoryLeavesTheMicClosed() {
        val h = Harness()
        h.failFactory = true
        h.start()
        h.swipeToB()
        h.advance(1_000) // 例外は握りつぶされ、何も開かない
        assertEquals(0, h.runtimes.size)
        assertFalse(h.pages.sessionActive)
    }

    @Test fun onlySwipeAndAutoNoticeEntriesCanStart() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.voice.cancel()
        for (reason in listOf(PageReason.IDLE_RETURN, PageReason.RESUME)) {
            h.voice.onEntered(DaidaiEntry(reason, false, true, null, true))
            h.advance(1_000)
        }
        assertEquals(0, h.factoryCalls)
    }

    @Test fun anEntryWithoutOpenMicOrWithoutConfigurationNeverStarts() {
        val h = Harness()
        h.start()
        h.swipeToB()
        h.voice.cancel()
        h.voice.onEntered(DaidaiEntry(PageReason.AUTO_NOTICE, true, openMic = false, noticeId = "n", configured = true, micDenial = MicDenial.COOLDOWN))
        h.advance(1_000)
        h.voice.onEntered(DaidaiEntry(PageReason.SWIPE, false, openMic = true, noticeId = null, configured = false))
        h.advance(1_000)
        assertEquals(0, h.factoryCalls)
    }
}
