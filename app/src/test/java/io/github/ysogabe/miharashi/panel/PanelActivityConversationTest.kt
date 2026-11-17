package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.TimeUnit

private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

private class ConvActivityRuntime(val settings: DaidaiSettings, val listener: VoiceSessionListener) : VoiceRuntime {
    val calls = mutableListOf<String>()
    val turnStarts = mutableListOf<TurnRequest>()
    val conversationEnds = mutableListOf<String>()
    var closed = false
    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) { calls += "oldStart" }
    override fun retry() { calls += "oldRetry" }
    override fun start(request: TurnRequest) { calls += "start"; turnStarts += request }
    override fun retry(request: TurnRequest) { calls += "retry" }
    override fun endConversation(handle: String) { calls += "endConversation"; conversationEnds += handle }
    override fun cancel() { calls += "cancel" }
    override fun stopMicNow() { calls += "stopMic" }
    override fun close() { closed = true; calls += "close" }
}

private const val HANDLE = "AAAAAAAAAAAAAAAAAAAAAA"

/**
 * 会話の離脱 5 経路(段階 2a-P の AC2。設計書(段階 2)§5.4・§5.5)を、`PanelActivity` の結線(ライフサイクル・スワイプ・待機・設定画面・
 * 暗闇での消灯)を通して確かめる。セッション一式は [ConvActivityRuntime]。どの経路でも、続きの作成 0 回・会話の終わりの通知(ハンドルを受け取っていれば)1 回で、
 * 背面は `close` より前に積まれる。通信は一切しない(消灯の経路の `KioskSync` は始めず、`send` が「停止中」を返す)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PanelActivityConversationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val runtimes = mutableListOf<ConvActivityRuntime>()
    private var tones = 0
    private var followTones = 0
    private val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
    private val manager get() = shadowOf(context.getSystemService(SensorManager::class.java))

    @Before fun install() {
        AutoSwitchDiagnostics.clear()
        // 挨拶の取得で本物のホストへ通信しない(段 G)
        PanelActivity.testCueFactory = CueSourceFactory { null }
        PanelActivity.testVoiceRuntimeFactory = VoiceRuntimeFactory { s, l -> ConvActivityRuntime(s, l).also { runtimes += it } }
        PanelActivity.testStartSound = object : StartSound {
            override fun play() { tones++ }
            override fun playFollow() { followTones++ }
        }
    }

    @After fun uninstall() {
        PanelActivity.testCueFactory = null
        PanelActivity.testVoiceRuntimeFactory = null
        PanelActivity.testStartSound = null
        AutoSwitchDiagnostics.clear()
    }

    private class Fixture(val controller: ActivityController<PanelActivity>) {
        val activity: PanelActivity = controller.get()
        val pager = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PagerLayout
        val panel = pager.pageA as PanelView
        val daidai = pager.pageB as DaidaiView
        private var down = 0L

        private fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }

        private fun swipe(from: Float, to: Float, steps: Int = 6, stepMs: Long = 20) {
            val w = pager.width.toFloat()
            val y = pager.height / 2f
            down = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, w * from, y)
            for (i in 1..steps) { idleFor(stepMs); send(MotionEvent.ACTION_MOVE, w * (from + (to - from) * i / steps), y) }
            idleFor(stepMs)
            send(MotionEvent.ACTION_UP, w * to, y)
        }

        fun toB() { swipe(0.9f, 0.3f); idleFor(400); check(pager.position == 1f) { "B に入れなかった" } }
        fun toA() { swipe(0.3f, 0.9f); idleFor(400); check(pager.position == 0f) { "A に戻れなかった" } }

        /** 設定画面を開く操作(PanelView が受け取る `onSettings`)をそのまま呼ぶ */
        fun openSettings() {
            val f = PanelView::class.java.getDeclaredField("onSettings").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(panel) as () -> Unit)()
        }

        /** `KioskSync` が呼ぶ入口へ新しい status を渡す */
        fun deliver(status: KioskStatus) {
            val m = PanelActivity::class.java.getDeclaredMethod("onKioskState", PanelState::class.java).apply { isAccessible = true }
            m.invoke(activity, PanelState().withStatus(status, SystemClock.elapsedRealtime()))
        }

        /** 始めていない(`running` でない)同期を結線する: 暗闇の消灯の経路を通るが、`send` は通信せずに「停止中」を返す */
        fun injectIdleSync() {
            val f = PanelActivity::class.java.getDeclaredField("sync").apply { isAccessible = true }
            f.set(activity, KioskSync(KioskClient("http://127.0.0.1:9", "panel-token-0123456789"), PanelState()) { })
        }

        fun finish() { controller.pause().stop().destroy() }
    }

    private fun start(prefs: android.content.SharedPreferences.Editor.() -> Unit = {}): Fixture {
        manager.addSensor(sensor)
        // Robolectric は未ロック時に任意の null コールバックを呼ぶため、スワイプロック状態で起動(ほかの Activity の試験と同じ)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().apply {
            // パネルの接続先(url/token)は入れない: キオスクの同期を始めず、通信しない。だいだいの接続はフェイクの runtime が受ける
            putString("daidai_url", "http://127.0.0.1:8800")
            putString("daidai_token", "bridge-token-0123456789")
            prefs()
        }.commit()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        check(f.pager.width > 0) { "layout を持たない" }
        return f
    }

    /** B に入って最初の往復が始まり、(`withHandle` なら)ハンドルを受け取り、往復が終わって残響の待ちに入った状態にする */
    private fun conversationInEchoWait(f: Fixture, withHandle: Boolean = true): ConvActivityRuntime {
        f.toB()
        idleFor(500)
        val rt = runtimes.single()
        val first = rt.turnStarts.single()
        assertEquals(ConversationParam(null), first.conversation)
        if (withHandle) rt.listener.onConversation(first.tag, ConversationInfo(HANDLE, true, true))
        idleFor(10)
        rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, first.tag) // 往復の終わり → 残響の待ち(300 ms)
        idleFor(50)
        assertEquals("残響の待ちの間はまだ続きを始めない", 1, rt.turnStarts.size)
        return rt
    }

    private fun assertLeft(rt: ConvActivityRuntime, withHandle: Boolean) {
        idleFor(3_000) // 残響の待ち(300 ms)も、その先も過ぎる
        assertEquals("続きの作成 0 回", 1, rt.turnStarts.size)
        assertEquals(
            "会話の終わりの通知(ハンドルを受け取っていれば 1 回、受け取っていなければ 0 回)",
            if (withHandle) listOf(HANDLE) else emptyList<String>(), rt.conversationEnds,
        )
        assertEquals(1, runtimes.size)
    }

    private fun route(withHandle: Boolean, leave: (Fixture) -> Unit) {
        val f = start()
        try {
            val rt = conversationInEchoWait(f, withHandle)
            leave(f)
            assertLeft(rt, withHandle)
        } finally { f.finish() }
    }

    // ---- 5 経路 × ハンドルあり・なし

    @Test fun routeBackToAEndsTheConversation() = route(true) { it.toA() }
    @Test fun routeBackToAWithoutAHandleSendsNoEnd() = route(false) { it.toA() }

    @Test fun routeClockEndsTheConversation() = route(true) { it.panel.onSleepChanged(true) }
    @Test fun routeClockWithoutAHandleSendsNoEnd() = route(false) { it.panel.onSleepChanged(true) }

    @Test fun routeSettingsScreenEndsTheConversation() = route(true) { it.openSettings() }
    @Test fun routeSettingsScreenWithoutAHandleSendsNoEnd() = route(false) { it.openSettings() }

    @Test fun routeBackgroundEndsTheConversationBeforeClose() {
        val f = start()
        try {
            val rt = conversationInEchoWait(f)
            f.controller.pause()
            assertTrue(rt.closed)
            assertEquals(listOf(HANDLE), rt.conversationEnds)
            assertTrue("終わりの通知は close より前に積む(executor の shutdown の前)", rt.calls.indexOf("endConversation") < rt.calls.indexOf("close"))
            assertEquals("終わりの通知は 1 回", 1, rt.calls.count { it == "endConversation" })
            idleFor(3_000)
            assertEquals("続きの作成 0 回", 1, rt.turnStarts.size)
        } finally { f.finish() }
    }

    @Test fun routeBackgroundWithoutAHandleSendsNoEnd() {
        val f = start()
        try {
            val rt = conversationInEchoWait(f, withHandle = false)
            f.controller.pause()
            assertTrue(rt.closed)
            assertTrue(rt.conversationEnds.isEmpty())
            idleFor(3_000)
            assertEquals(1, rt.turnStarts.size)
        } finally { f.finish() }
    }

    /**
     * モニター消灯: 暗闇が 30 秒続き、キオスクの状態が新しい(5 秒以内)とき、`PanelActivity.checkDarkness` が `voice.cancel()` を呼んでから消灯を送る。
     * 同期は始めていないものを結線して、送信は「停止中」で通信しない。残響の待ちがこの消灯の直後に明けるように往復の終わりを置く。
     */
    @Test fun routeMonitorOffEndsTheConversationBeforeTheEchoWaitEnds() {
        val f = start { putInt("daidai_gate_dark_seconds", 90) } // 在席ゲートはまだ不在にならない(消灯の経路を通す)
        try {
            f.injectIdleSync()
            f.toB()
            idleFor(500)
            val rt = runtimes.single()
            val first = rt.turnStarts.single()
            rt.listener.onConversation(first.tag, ConversationInfo(HANDLE, true, true))
            idleFor(10)
            val display = KioskStatus.parse(javaClass.getResource("/fixtures/status-display.json")!!.readText())
                .copy(display = DisplayInfo(true, "auto", true))
            manager.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(0f)).build(), sensor)
            idleFor(27_000)
            f.deliver(display) // 暗闇が 27 秒続いた時点の新しい状態(5 秒以内なので 30 秒の時点でも新しい)
            idleFor(2_800)
            assertTrue("消灯の前: 会話は続いている", rt.conversationEnds.isEmpty())
            rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, first.tag) // 残響の待ちの予約は暗闇の 30.1 秒(消灯の 0.1 秒後)
            idleFor(3_000) // 暗闇の 30 秒で消灯の経路が cancel を呼び、予約は消える
            assertEquals("続きの作成 0 回", 1, rt.turnStarts.size)
            assertEquals(listOf(HANDLE), rt.conversationEnds)
            assertEquals("消灯の経路で取り消した", 1, rt.calls.count { it == "endConversation" })
            assertTrue(rt.calls.indexOf("endConversation") < rt.calls.lastIndexOf("cancel"))
        } finally { f.finish() }
    }

    // ---- 在席ゲートの結線(S2-U9。PanelActivity が本物の照度ゲートを DaidaiVoice に渡している)

    private fun lux(v: Float) =
        manager.sendSensorEventToListeners(SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(v)).build(), sensor)

    /** 暗闇(または明るさ)が 31 秒続き(既定のゲート 30 秒を過ぎる)たあと、1 往復目が再生まで終わる。パネルの接続先は空なので、消灯の経路は通らない */
    private fun finishFirstTurnAfter31SecondsAt(lux: Float): ConvActivityRuntime {
        val f = start()
        fixtures += f
        f.toB()
        idleFor(500)
        val rt = runtimes.single()
        val first = rt.turnStarts.single()
        rt.listener.onConversation(first.tag, ConversationInfo(HANDLE, true, true))
        lux(lux)
        idleFor(31_000)
        rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, first.tag)
        idleFor(2_000)
        return rt
    }

    private val fixtures = mutableListOf<Fixture>()

    @After fun finishFixtures() { fixtures.forEach { it.finish() } }

    @Test fun theRealAmbientLightGateEndsTheConversationInTheDarkRoom() {
        val rt = finishFirstTurnAfter31SecondsAt(0f)
        assertEquals("暗闇が続いたら(不在)続きは作らない", 1, rt.turnStarts.size)
        assertEquals(listOf(HANDLE), rt.conversationEnds)
    }

    @Test fun theRealAmbientLightGateLetsTheConversationContinueInABrightRoom() {
        val rt = finishFirstTurnAfter31SecondsAt(180f)
        assertEquals("明るければ続く", 2, rt.turnStarts.size)
        assertTrue(rt.turnStarts[1].follow)
        assertEquals(ConversationParam(HANDLE), rt.turnStarts[1].conversation)
        assertTrue(rt.conversationEnds.isEmpty())
    }

    // ---- 無操作タイマー(結線を通す)

    @Test fun theIdleTimerStaysStoppedAcrossTheEchoWaitAndRestartsFromTheConversationEnd() {
        val f = start { putInt("daidai_idle_seconds", 10) }
        try {
            f.toB()
            idleFor(500)
            val rt = runtimes.single()
            val first = rt.turnStarts.single()
            rt.listener.onConversation(first.tag, ConversationInfo(HANDLE, true, true))
            rt.listener.onEnded(false, "done", TurnOutcome.PLAYED, first.tag)
            idleFor(350) // 残響の待ちが明けて、続きの往復の作成の待ちに入る(runtime はフェイクなので何も返さない)
            assertEquals(2, rt.turnStarts.size)
            idleFor(60_000)
            assertEquals("往復の間・作成の待ちの間は A に戻らない", 1f, f.pager.position, 0f)
            val second = rt.turnStarts[1]
            assertTrue(second.follow)
            rt.listener.onEnded(false, "no_speech", TurnOutcome.ERROR, second.tag) // 続きの無発話で会話が終わる
            idleFor(9_000)
            assertEquals("会話の終わりから数え直す(10 秒に満たない)", 1f, f.pager.position, 0f)
            idleFor(3_000)
            assertEquals("会話の終わりから設定の秒数で A に戻る", 0f, f.pager.position, 0f)
            assertEquals(listOf(HANDLE), rt.conversationEnds)
        } finally { f.finish() }
    }
}
