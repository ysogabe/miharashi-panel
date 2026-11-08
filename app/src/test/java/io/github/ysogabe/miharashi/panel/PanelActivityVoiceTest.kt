package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
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
import java.util.concurrent.TimeUnit

private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

private class ActivityRuntime(val settings: DaidaiSettings, val listener: VoiceSessionListener) : VoiceRuntime {
    val calls = mutableListOf<String>()
    var closed = false
    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) { calls += "start:${reason.wire}:$autoStarted" }
    override fun retry() { calls += "retry" }
    override fun cancel() { calls += "cancel" }
    override fun stopMicNow() { calls += "stopMic" }
    override fun close() { closed = true; calls += "close" }
}

/** `PanelActivity` の実物(ライフサイクル・スワイプ・メインのループ)にセッション一式のフェイクを載せた結線の確認。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PanelActivityVoiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val runtimes = mutableListOf<ActivityRuntime>()
    private var tones = 0

    @Before fun install() {
        PanelActivity.testVoiceRuntimeFactory = VoiceRuntimeFactory { s, l -> ActivityRuntime(s, l).also { runtimes += it } }
        PanelActivity.testStartSound = StartSound { tones++ }
    }

    @After fun uninstall() {
        PanelActivity.testVoiceRuntimeFactory = null
        PanelActivity.testStartSound = null
    }

    private class Fixture(val controller: ActivityController<PanelActivity>) {
        val activity: PanelActivity = controller.get()
        val pager = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PagerLayout
        val panel = pager.pageA as PanelView
        val daidai = pager.pageB as DaidaiView
        private var down = 0L

        private fun send(action: Int, x: Float, y: Float, view: ViewGroup = pager) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }

        fun swipe(from: Float, to: Float, steps: Int = 6, stepMs: Long = 20) {
            val w = pager.width.toFloat()
            val y = pager.height / 2f
            down = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, w * from, y)
            for (i in 1..steps) { idle(stepMs); send(MotionEvent.ACTION_MOVE, w * (from + (to - from) * i / steps), y) }
            idle(stepMs)
            send(MotionEvent.ACTION_UP, w * to, y)
        }

        fun toB() { swipe(0.9f, 0.3f); idle(400); check(pager.position == 1f) { "B に入れなかった: ${pager.position}" } }
        fun toA() { swipe(0.3f, 0.9f); idle(400); check(pager.position == 0f) { "A に戻れなかった: ${pager.position}" } }

        /** B の上の短いタップ */
        fun tap() {
            val t = SystemClock.uptimeMillis()
            val x = pager.width / 2f
            val y = pager.height / 2f
            pager.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            pager.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        }

        fun finish() { controller.pause().stop().destroy() }
    }

    private fun start(configured: Boolean, prefs: Context.() -> Unit = {}): Fixture {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val edit = context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit()
        if (configured) {
            edit.putString("url", "http://192.168.0.132:8790").putString("token", "panel-token-0123456789")
                .putString("daidai_url", "http://192.168.0.132:8800").putString("daidai_token", "bridge-token-0123456789")
        }
        edit.commit()
        context.prefs()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        check(f.pager.width > 0) { "layout を持たない" }
        return f
    }

    @Test fun unconfiguredBridgeNeverCreatesASessionOrOpensTheMic() {
        val f = start(configured = false)
        try {
            f.toB()
            idle(2_000)
            assertEquals("未設定ではセッションを作らない", 0, runtimes.size)
            assertEquals(SetupGuide.NOT_CONFIGURED, f.daidai.uiState.setup)
        } finally { f.finish() }
    }

    @Test fun swipeIntoBStartsTheSessionAfterTheDebounceAndLeavingCancelsIt() {
        val f = start(configured = true)
        try {
            f.toA() // 念のため A にいる
            f.swipe(0.9f, 0.3f)
            idle(100)
            assertEquals("ドラッグ・アニメーション中は開かない", 0, runtimes.size)
            idle(500)
            assertEquals(1, runtimes.size)
            assertTrue(runtimes[0].calls.contains("start:swipe:false"))
            f.toA()
            assertTrue("B を離れたら取り消す", runtimes[0].calls.takeLast(5).contains("cancel"))
            assertTrue(runtimes[0].calls.contains("stopMic"))
        } finally { f.finish() }
    }

    @Test fun onPauseCancelsAndDisposesTheRuntimeAndResumeStartsNothing() {
        val f = start(configured = true)
        try {
            f.toB()
            idle(500)
            val rt = runtimes.single()
            f.controller.pause()
            assertTrue(rt.calls.contains("cancel"))
            assertTrue(rt.calls.contains("stopMic"))
            assertTrue("セッションのスレッドなども片づける", rt.closed)
            f.controller.resume()
            idle(3_000)
            assertEquals("onResume は常に A から始まり、セッションを始めない", 1, runtimes.size)
            assertEquals(0f, f.pager.position, 0f)
        } finally { f.finish() }
    }

    @Test fun enteringStandbyCancelsTheSession() {
        val f = start(configured = true)
        try {
            f.toB()
            idle(500)
            val rt = runtimes.single()
            val before = rt.calls.count { it == "cancel" }
            f.panel.onSleepChanged(true) // 待機(時計)に入った
            assertTrue(rt.calls.count { it == "cancel" } > before)
            assertTrue(rt.calls.contains("stopMic"))
        } finally { f.finish() }
    }

    @Test fun anIntentCannotOpenTheMicOrChangeThePage() {
        val f = start(configured = true)
        try {
            val intent = android.content.Intent(context, PanelActivity::class.java)
                .putExtra("page", "B").putExtra("mic", true).putExtra("daidai", true).putExtra("start", "swipe")
            f.controller.newIntent(intent)
            f.controller.pause().resume()
            idle(3_000)
            assertEquals(0f, f.pager.position, 0f)
            assertEquals(0, runtimes.size)
        } finally { f.finish() }
    }

    @Test fun errorSnapshotShowsAndTapRetriesOnlyOnError() {
        val f = start(configured = true)
        try {
            f.toB()
            idle(500)
            val rt = runtimes.single()
            // IDLE のタップは無視
            f.tap()
            assertFalse(rt.calls.contains("retry"))
            rt.listener.onSnapshot(VoiceSnapshot(state = VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE))
            idle(50)
            assertEquals(VoiceState.ERROR, f.daidai.uiState.voice)
            f.tap()
            assertEquals(1, rt.calls.count { it == "retry" })
            idle(60_000)
            assertEquals("自動では再試行しない", 1, rt.calls.count { it == "retry" })
        } finally { f.finish() }
    }

    @Test fun micIndicatorAndSessionActiveFollowSnapshots() {
        val f = start(configured = true) { getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putInt("daidai_idle_seconds", 10).commit() }
        try {
            f.toB()
            idle(500)
            val rt = runtimes.single()
            rt.listener.onSnapshot(VoiceSnapshot(state = VoiceState.LISTENING, micActive = true, active = true))
            idle(50)
            assertTrue(f.daidai.uiState.micActive)
            // 進行中は B の無操作タイマーが止まる
            idle(30_000)
            assertEquals("セッション進行中は A に戻らない", 1f, f.pager.position, 0f)
            rt.listener.onSnapshot(VoiceSnapshot(state = VoiceState.IDLE, micActive = false, active = false))
            idle(50)
            assertFalse(f.daidai.uiState.micActive)
            idle(15_000)
            assertEquals("終われば無操作で A に戻る", 0f, f.pager.position, 0f)
        } finally { f.finish() }
    }

    @Test fun changingTheBridgeSettingsRebuildsTheRuntimeOnResume() {
        val f = start(configured = true)
        try {
            f.toB()
            idle(500)
            assertEquals(1, runtimes.size)
            f.controller.pause()
            context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putString("daidai_url", "http://192.168.0.200:8800").commit()
            f.controller.resume()
            f.toB()
            idle(500)
            assertEquals(2, runtimes.size)
            assertEquals("http://192.168.0.200:8800", runtimes[1].settings.url)
        } finally { f.finish() }
    }

    @Test fun clearingTheBridgeUrlStopsTheVoiceFeatureCompletely() {
        val f = start(configured = true)
        try {
            f.controller.pause()
            context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putString("daidai_url", "").commit()
            f.controller.resume()
            f.toB()
            idle(2_000)
            assertEquals(0, runtimes.size)
            assertEquals(SetupGuide.NOT_CONFIGURED, f.daidai.uiState.setup)
        } finally { f.finish() }
    }
}
