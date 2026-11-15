package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `DaidaiController` の結線(AC9, AC12)。純 JVM(仮想時計・実物の `PageController`・`AmbientLightGate`・`AutoSwitchPolicy`)。
 * 実際の Activity・センサー・ライフサイクルは `DaidaiAutoSwitchActivityTest`。
 */
class DaidaiControllerTest {
    private val configuredSettings = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    private inner class Harness(var settings: DaidaiSettings = configuredSettings) {
        var now = 1_000L
        val pages = PageController()
        val rendered = mutableListOf<DaidaiUiState>()
        var wakes = 0
        val controller = DaidaiController(pages, { settings }, { rendered += it }, { wakes++ }, { now })
        val entered = mutableListOf<DaidaiEntry>()
        var lefts = 0

        init {
            controller.entryListener = object : DaidaiEntryListener {
                override fun onEntered(entry: DaidaiEntry) { entered += entry }
                override fun onLeft() { lefts++ }
            }
        }

        fun start(initial: PanelState = PanelState(), endpoint: Any? = "ep") = controller.start(initial, endpoint)

        fun feed(status: KioskStatus) = controller.onKioskState(PanelState().withStatus(status, now))

        /** 自動切替のアニメーションが終わった */
        fun settle() = pages.animationEnded(now)

        /** 利用者が左へスワイプして B に入る */
        fun swipeToB() {
            assertTrue(pages.beginDrag(now))
            pages.endDrag(Page.B, now)
            settle()
        }

        fun advance(ms: Long) { now += ms }
        val ui get() = rendered.last()
    }

    @Before fun clearDiagnostics() = AutoSwitchDiagnostics.clear()
    @After fun clearDiagnosticsAfter() = AutoSwitchDiagnostics.clear()

    // --- status の組み立て ---

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun notice(id: String, age: Long = 3, priority: String = "normal") =
        JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", age).put("priority", priority)

    private fun status(rev: Long?, vararg ns: JSONObject, pose: String? = null): KioskStatus {
        val o = JSONObject(fixture("status-photo.json"))
        val d = JSONObject().put("notices", JSONArray(ns.toList())).put("ack", true).put("unseen", ns.size)
        if (rev != null) d.put("rev", rev)
        if (pose != null) d.put("avatar_pose", pose)
        return KioskStatus.parse(o.put("daidai", d).toString())
    }

    private fun legacyStatus() = KioskStatus.parse(fixture("status-photo.json"))

    /** 基準を取って、明るい環境にしておく */
    private fun Harness.ready(lux: Float? = 180f) {
        start()
        if (lux != null) controller.onLux(lux)
        feed(status(1))
    }

    private val line get() = AutoSwitchDiagnostics.line

    // --- 切替 ---

    @Test fun brightEnvironmentSwitchesToBOnNewNotice() {
        val h = Harness()
        h.ready()
        h.advance(1_000)
        h.feed(status(2, notice("n1")))
        assertEquals(Page.B, h.pages.targetPage)
        assertEquals("待機時計を解除してから動く", 1, h.wakes)
        assertEquals("切替: 明るい (180 lx)", line)
        assertTrue("アニメーション中はまだ入場ではない", h.entered.isEmpty())
        h.settle()
        assertEquals(Page.B, h.pages.settledPage)
        val e = h.entered.single()
        assertEquals(PageReason.AUTO_NOTICE, e.reason)
        assertTrue(e.autoStarted)
        assertTrue(e.openMic)
        assertEquals("n1", e.noticeId)
        assertTrue(e.configured)
        assertNull(e.micDenial)
    }

    @Test fun darkTwentyNineSecondsSwitchesAndThirtyDoesNot() {
        val h = Harness()
        h.start()
        h.controller.onLux(0.4f)
        h.feed(status(1))
        h.advance(29_000)
        h.feed(status(2, notice("n1")))
        assertEquals(Page.B, h.pages.targetPage)
        assertEquals("切替: 暗くなって間もない (0.4 lx, 29 秒)", line)

        val h2 = Harness()
        h2.start()
        h2.controller.onLux(0.4f)
        h2.feed(status(1))
        h2.advance(30_000)
        h2.feed(status(2, notice("n1")))
        assertEquals(Page.A, h2.pages.targetPage)
        assertEquals(0, h2.wakes)
        assertEquals("見送り: 暗闇 (0.4 lx, 30 秒)", AutoSwitchDiagnostics.line)
        h2.advance(22_000)
        h2.feed(status(3, notice("n2"), notice("n1")))
        assertEquals("見送り: 暗闇 (0.4 lx, 52 秒)", AutoSwitchDiagnostics.line)
    }

    @Test fun unknownLuxNeverSwitches() {
        val h = Harness()
        h.ready(lux = null) // 値が未着(センサーなし・登録失敗・onResume 直後)
        h.feed(status(2, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("見送り: 照度が取れない", line)
        h.controller.onLux(null)
        h.feed(status(3, notice("n2"), notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("見送り: 照度が取れない", line)
    }

    @Test fun sameNoticeIdSwitchesOnlyOnce() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("n1")))
        h.settle()
        assertEquals(1, h.wakes)
        // SSE とポーリングの二重(同じ rev・別の rev でも同じ id)
        h.feed(status(2, notice("n1", age = 4)))
        h.feed(status(3, notice("n1", age = 5)))
        // 利用者が A に戻ったあとにまた同じ通知を含む status が来ても切り替えない
        h.pages.beginDrag(h.now); h.pages.endDrag(Page.A, h.now); h.settle()
        h.feed(status(4, notice("n1", age = 6)))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(1, h.wakes)
        assertEquals(1, h.entered.size)
    }

    @Test fun noticesAlreadyPresentAtTheFirstStatusNeverSwitch() {
        // アプリ起動前の通知、起動から最初の status までの通知
        val h = Harness()
        h.start()
        h.controller.onLux(180f)
        h.feed(status(5, notice("old", age = 2)))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("基準を取るだけで判定は残さない", AutoSwitchDiagnostics.NONE_TEXT, line)
        assertNotNull("表示用のデータは持つ", h.ui.notice)
        h.feed(status(6, notice("new"), notice("old", age = 3)))
        assertEquals(Page.B, h.pages.targetPage)
    }

    @Test fun tooOldLowPriorityAndStaleRevSnapshotNeverSwitch() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("aged", age = 31)))
        h.feed(status(3, notice("low", age = 1, priority = "low"), notice("aged", age = 32)))
        h.feed(status(1, notice("late", age = 1)))               // rev が古い後着
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(0, h.wakes)
        // age_s がちょうど 30 なら切り替える
        h.feed(status(4, notice("edge", age = 30), notice("low", age = 5, priority = "low")))
        assertEquals(Page.B, h.pages.targetPage)
    }

    @Test fun fixturesBaselineThenLowThenStaleRev() {
        val h = Harness()
        h.start()
        h.controller.onLux(180f)
        h.feed(KioskStatus.parse(fixture("status-daidai-empty.json")))     // rev 129 が基準
        h.feed(KioskStatus.parse(fixture("status-daidai-low.json")))       // rev 130 low → 切替しない
        h.feed(KioskStatus.parse(fixture("status-daidai-stale-rev.json"))) // rev 120 は捨てる
        assertEquals(Page.A, h.pages.targetPage)
        val next = JSONObject(fixture("status-daidai.json")).also { it.getJSONObject("daidai").put("rev", 131) }
        h.feed(KioskStatus.parse(next.toString()))
        assertEquals("rev 131 の新着(age 12)は切り替える", Page.B, h.pages.targetPage)
    }

    @Test fun legacyStatusWithoutDaidaiChangesNothing() {
        val h = Harness()
        h.start()
        h.controller.onLux(180f)
        h.feed(legacyStatus())
        h.feed(legacyStatus())
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(0, h.wakes)
        assertEquals(AutoSwitchDiagnostics.NONE_TEXT, line)
        assertEquals("B の表示は変わらない", DaidaiUiState.forSettings(true), h.ui)
        // daidai が現れた最初の status が基準になる(旧ホストから新ホストへ)
        h.feed(status(7, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        h.feed(status(8, notice("n2"), notice("n1")))
        assertEquals(Page.B, h.pages.targetPage)
    }

    @Test fun hostWithoutRevNeverSwitchesButShowsTheCard() {
        val h = Harness()
        h.ready()
        h.feed(status(null, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("n1", h.ui.notice?.id)
    }

    @Test fun disabledAutoSwitchNeverSwitches() {
        val h = Harness(configuredSettings.copy(autoSwitch = false))
        h.ready()
        h.feed(status(2, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(0, h.wakes)
        assertEquals("見送り: 自動切替が無効", line)
    }

    // --- onResume のたびの基準 ---

    @Test fun everyStartTakesANewBaseline() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("a")))        // 切替(前面中の通知)
        h.settle()
        h.pages.onPause(h.now)
        h.controller.stop()
        // 背面・設定画面にいた間に来た通知は、戻ってからの最初の status に入っているだけ
        h.advance(60_000)
        h.pages.onResume(h.now)
        h.start()
        h.controller.onLux(180f)
        h.feed(status(5, notice("while-away", age = 5), notice("a", age = 70)))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(1, h.wakes)
        // それ以降の新着は切り替える
        h.feed(status(6, notice("fresh"), notice("while-away", age = 8)))
        assertEquals(Page.B, h.pages.targetPage)
        assertEquals(2, h.wakes)
    }

    @Test fun staleStatusKeptFromBeforeIsNotTheBaselineAndErrorRedeliveryIsIgnored() {
        val h = Harness()
        val old = PanelState().withStatus(status(5, notice("old")), 0)
        h.start(initial = old)
        h.controller.onLux(180f)
        h.controller.onKioskState(old.withError("timeout"))   // エラーでの再配信
        h.controller.onKioskState(old)
        assertEquals(Page.A, h.pages.targetPage)
        // 最初の新しい status が基準(通知が混じっていても切り替えない)
        h.feed(status(6, notice("while-away"), notice("old", age = 40)))
        assertEquals(Page.A, h.pages.targetPage)
        h.feed(status(7, notice("fresh"), notice("while-away", age = 2)))
        assertEquals(Page.B, h.pages.targetPage)
    }

    @Test fun endpointChangeDropsDisplayedNoticesAndRebaselines() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("a")))
        h.settle()
        assertEquals("a", h.ui.notice?.id)
        h.pages.onPause(h.now); h.controller.stop(); h.pages.onResume(h.now)
        h.start(endpoint = "other")
        assertNull("前の接続先の通知カードは出さない", h.ui.notice)
    }

    @Test fun nothingHappensWhileStopped() {
        val h = Harness()
        h.ready()
        h.controller.stop()
        h.controller.onLux(180f)
        h.feed(status(2, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(0, h.wakes)
    }

    @Test fun stopDropsLuxSoItIsUnknownUntilTheNextReading() {
        val h = Harness()
        h.ready()
        h.pages.onPause(h.now); h.controller.stop(); h.pages.onResume(h.now)
        h.start()
        h.feed(status(5))                      // 基準
        h.feed(status(6, notice("n1")))        // 次のセンサー値が来るまで UNKNOWN
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("見送り: 照度が取れない", line)
        h.controller.onLux(120f)
        h.feed(status(7, notice("n2"), notice("n1")))
        assertEquals(Page.B, h.pages.targetPage)
    }

    @Test fun darkTrackingDoesNotSpanBackgrounding() {
        val h = Harness()
        h.start()
        h.controller.onLux(0f)                 // 暗闇の開始
        h.feed(status(1))
        h.advance(20_000)
        h.pages.onPause(h.now); h.controller.stop()
        h.advance(120_000)                     // 背面の 2 分は暗闇の継続に含めない
        h.pages.onResume(h.now); h.start()
        h.controller.onLux(0f)
        h.feed(status(5))
        h.advance(5_000)
        h.feed(status(6, notice("n1")))
        assertEquals("暗闇は戻ってから数え直す(5 秒)", Page.B, h.pages.targetPage)
    }

    // --- 設定の反映 ---

    @Test fun settingsChangeAppliesFromTheNextJudgement() {
        val h = Harness()
        h.ready(lux = 5f)
        h.feed(status(2, notice("n1")))
        assertEquals("5 lx は閾値 1 lx より明るい", Page.B, h.pages.targetPage)
        h.settle()
        h.pages.beginDrag(h.now); h.pages.endDrag(Page.A, h.now); h.settle() // A に戻す
        // 閾値と継続秒数が変わった: 次の判定から、5 lx も暗闇で 0 秒で見送り
        h.settings = configuredSettings.copy(gateDarkLux = 10f, gateDarkSeconds = 0)
        h.feed(status(3, notice("n2"), notice("n1")))
        assertEquals("見送り: 暗闇 (5.0 lx, 0 秒)", line)
    }

    // --- B にいるとき・操作中 ---

    @Test fun onBOnlyTheCardIsUpdated() {
        val h = Harness()
        h.ready()
        h.swipeToB()
        assertEquals(PageReason.SWIPE, h.entered.single().reason)
        h.feed(status(2, notice("n1"), notice("older", age = 100), pose = "talking"))
        assertEquals("n1", h.ui.notice?.id)
        assertEquals(1, h.ui.moreNotices)
        assertEquals("talking", h.ui.noticePose)
        assertEquals(0, h.wakes)
        assertEquals(Page.B, h.pages.targetPage)
        assertEquals("更新のみ: すでにだいだい画面を表示中", line)
        assertEquals("入場を二重に通知しない", 1, h.entered.size)
    }

    @Test fun duringDragItDefersAndReevaluatesExactlyOnceAfterTheDragIsAbandoned() {
        val h = Harness()
        h.ready()
        assertTrue(h.pages.beginDrag(h.now))
        h.feed(status(2, notice("n1")))
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals(0, h.wakes)
        assertEquals("保留: 操作中(終わってから判定し直す)", line)
        // 利用者が元のページに戻した → onSettled は来ない(ページは変わらない)が、onIdle で再評価される
        h.advance(500)
        h.pages.endDrag(Page.A, h.now)
        assertEquals("アニメーション中はまだ", 0, h.wakes)
        h.settle()
        assertEquals(Page.B, h.pages.targetPage)
        assertEquals(1, h.wakes)
        assertEquals("切替: 明るい (180 lx)", line)
        h.settle() // 自動切替のアニメーションが終わる。再評価はもう起きない
        assertEquals(1, h.wakes)
        assertEquals(PageReason.AUTO_NOTICE, h.entered.single().reason)
    }

    @Test fun deferredCandidateIsDroppedWhenTheUserLandsOnB() {
        val h = Harness()
        h.ready()
        assertTrue(h.pages.beginDrag(h.now))
        h.feed(status(2, notice("n1")))
        h.pages.endDrag(Page.B, h.now)
        h.settle()
        assertEquals(Page.B, h.pages.settledPage)
        assertEquals("利用者が自分で B に入った: 切り替えない", 0, h.wakes)
        assertEquals("更新のみ: すでにだいだい画面を表示中", line)
        assertEquals(PageReason.SWIPE, h.entered.single().reason)
    }

    @Test fun deferredCandidateThatGotTooOldIsSkipped() {
        val h = Harness()
        h.ready()
        assertTrue(h.pages.beginDrag(h.now))
        h.feed(status(2, notice("n1", age = 5)))
        h.advance(26_000)
        h.pages.endDrag(Page.A, h.now)
        h.settle()
        assertEquals(Page.A, h.pages.targetPage)
        assertEquals("見送り: 古い通知", line)
    }

    @Test fun deferredCandidateIsDroppedOnStop() {
        val h = Harness()
        h.ready()
        h.pages.beginDrag(h.now)
        h.feed(status(2, notice("n1")))
        h.controller.stop()
        h.pages.endDrag(Page.A, h.now)
        h.settle()
        assertEquals(0, h.wakes)
        assertEquals(Page.A, h.pages.targetPage)
    }

    // --- 入場の情報(後続の音声タスクへ) ---

    @Test fun entryCarriesMicBrakesAndConfiguration() {
        val h = Harness(configuredSettings.copy(autoListen = false))
        h.ready()
        h.feed(status(2, notice("n1")))
        h.settle()
        val e = h.entered.single()
        assertEquals(PageReason.AUTO_NOTICE, e.reason)
        assertFalse("auto_listen オフでは切り替えてもマイクは開かない", e.openMic)
        assertEquals(MicDenial.AUTO_LISTEN_OFF, e.micDenial)
        assertEquals("切替: 明るい (180 lx)、マイクは開かない: 自動で聞く設定がオフ", line)
    }

    @Test fun unconfiguredBridgeNeverOpensMicAndShowsGuide() {
        val h = Harness(DaidaiSettings())
        h.ready()
        assertEquals(SetupGuide.NOT_CONFIGURED, h.ui.setup)
        h.feed(status(2, notice("n1")))
        h.settle()
        assertEquals("通知では B へ切り替わる(案内を見せる)", Page.B, h.pages.settledPage)
        val e = h.entered.single()
        assertFalse(e.configured)
        assertFalse(e.openMic)
        // スワイプでも同じ
        h.pages.beginDrag(h.now); h.pages.endDrag(Page.A, h.now); h.settle()
        h.swipeToB()
        assertFalse(h.entered.last().openMic)
        assertFalse(h.entered.last().autoStarted)
    }

    @Test fun swipeEntryOpensMicWhenConfiguredAndLeavingIsReported() {
        val h = Harness()
        h.ready()
        h.swipeToB()
        val e = h.entered.single()
        assertEquals(PageReason.SWIPE, e.reason)
        assertFalse(e.autoStarted)
        assertTrue(e.openMic)
        assertNull(e.noticeId)
        h.pages.onPause(h.now) // 背面でも退場が通知される
        assertEquals(1, h.lefts)
    }

    @Test fun cooldownBrakeFlowsToTheEntryOnlyAfterTheMicWasActuallyStarted() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("n1")))
        h.settle()
        assertTrue(h.entered.last().openMic)
        // T5 はマイクを開かない: 開いたと記録するのは音声タスク。記録されるとクールダウンが効く
        h.controller.policy.onAutoMicStarted(h.now)
        h.pages.beginDrag(h.now); h.pages.endDrag(Page.A, h.now); h.settle()
        h.advance(60_000)
        h.feed(status(3, notice("n2"), notice("n1")))
        h.settle()
        assertEquals(MicDenial.COOLDOWN, h.entered.last().micDenial)
        assertFalse(h.entered.last().openMic)
    }

    // --- 表示データ ---

    @Test fun uiShowsLatestNoticeAndMoreCountAndPose() {
        val h = Harness()
        h.start()
        h.feed(status(1, notice("c"), notice("b"), notice("a"), pose = "working"))
        assertEquals("c", h.ui.notice?.id)
        assertEquals(2, h.ui.moreNotices)
        assertEquals("working", h.ui.noticePose)
        assertEquals(SetupGuide.NONE, h.ui.setup)
        h.feed(status(2))
        assertNull(h.ui.notice)
        assertEquals(0, h.ui.moreNotices)
    }

    @Test fun diagnosticsAndStatesDoNotExposeNoticeText() {
        val h = Harness()
        h.ready()
        h.feed(status(2, notice("n1")))
        assertFalse(line.contains("t-n1") || line.contains("b-n1"))
        assertFalse(h.ui.toString().contains("t-n1"))
    }
}
