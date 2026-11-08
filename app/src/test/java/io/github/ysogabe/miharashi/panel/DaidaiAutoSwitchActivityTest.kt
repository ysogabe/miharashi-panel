package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

/**
 * 通知による自動切替の結線(AC9, AC12)。実際の Activity・センサー(`ShadowSensor`)・ページャ・待機時計を通す。
 * 通知は `onKioskState`(`KioskSync` が呼ぶ入口)へ fixture から作った status を流して再現する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DaidaiAutoSwitchActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
    private val manager get() = shadowOf(context.getSystemService(SensorManager::class.java))

    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private inner class Fixture(val controller: ActivityController<PanelActivity>) {
        val activity: PanelActivity = controller.get()
        val pager = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PagerLayout
        val panel = pager.pageA as PanelView
        val daidaiView = pager.pageB as DaidaiView
        val sleeps = mutableListOf<Boolean>()

        init {
            val original = panel.onSleepChanged
            panel.onSleepChanged = { sleeps += it; original(it) }
        }

        fun lux(v: Float) = manager.sendSensorEventToListeners(
            SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(v)).build(), sensor)

        /** `KioskSync` が呼ぶ入口へ新しい status を渡す */
        fun deliver(status: KioskStatus) {
            val m = PanelActivity::class.java.getDeclaredMethod("onKioskState", PanelState::class.java).apply { isAccessible = true }
            m.invoke(activity, PanelState().withStatus(status, SystemClock.elapsedRealtime()))
        }

        private var down = 0L
        fun touch(action: Int, fraction: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pager.width * fraction, pager.height / 2f, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }

        fun press(fraction: Float) { down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, fraction) }

        val onB get() = pager.position == 1f
        fun finish() { controller.pause().stop().destroy() }
    }

    private fun start(prefs: android.content.SharedPreferences.Editor.() -> Unit = {}): Fixture {
        manager.addSensor(sensor)
        // Robolectric 4.14 は未ロック時に任意の null コールバックを呼ぶため、スワイプロック状態で起動。
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().apply {
            putString("daidai_url", "http://192.168.0.132:8800")
            putString("daidai_token", "bridge-token-0123456789")
            prefs()
        }.commit()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        check(f.pager.width > 0) { "layout を持たない" }
        return f
    }

    // --- status の組み立て(fixture を土台にする) ---

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun notice(id: String, age: Long = 3, priority: String = "normal") =
        JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", age).put("priority", priority)

    private fun status(rev: Long, vararg ns: JSONObject): KioskStatus {
        val o = JSONObject(fixture("status-daidai-empty.json"))
        o.getJSONObject("daidai").put("rev", rev).put("notices", JSONArray(ns.toList()))
        return KioskStatus.parse(o.toString())
    }

    // --- 時計中でも動く ---

    @Test fun brightNoticeSwitchesToBEvenWhileTheIdleClockIsShown() {
        val f = start()
        try {
            f.lux(180f)
            f.panel.sleepTimeoutMs = 1_000
            idle(2_000)
            assertEquals("時計が出ている", listOf(true), f.sleeps)
            f.deliver(status(1))                         // 基準
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertTrue(f.onB)
            assertEquals("時計は先に解除される", listOf(true, false), f.sleeps)
            assertTrue(f.panel.idleSuspended)
            assertEquals("t-n1", f.daidaiView.uiState.notice?.title)
            assertEquals("切替: 明るい (180 lx)", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    @Test fun darknessTwentyNineSecondsSwitchesThirtyDoesNot() {
        for ((seconds, expectB) in listOf(29L to true, 30L to false)) {
            val f = start()
            try {
                f.lux(0.4f)
                f.deliver(status(1))
                idle(seconds * 1_000)
                f.deliver(status(2, notice("n1")))
                idle(400)
                assertEquals("暗闇 $seconds 秒", expectB, f.onB)
                if (!expectB) assertEquals("見送り: 暗闇 (0.4 lx, 30 秒)", AutoSwitchDiagnostics.line)
            } finally { f.finish() }
        }
    }

    @Test fun noSensorValueMeansNoSwitch() {
        val f = start()
        try {
            f.deliver(status(1))
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertFalse(f.onB)
            assertEquals("見送り: 照度が取れない", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    @Test fun sensorWithoutAnyRegisteredLightSensorIsUnknown() {
        // センサーがない端末
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().commit()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        try {
            f.deliver(status(1))
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertFalse(f.onB)
            assertEquals("見送り: 照度が取れない", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    // --- 照度購読の条件 ---

    @Test fun lightSensorIsSubscribedWhenOnlyAutoSwitchIsEnabled() {
        val f = start { putBoolean("auto_brightness", false); putBoolean("dark_off", false); putBoolean("daidai_auto_switch", true) }
        try {
            assertTrue(manager.listeners.isNotEmpty())
            f.lux(180f)
            // 調光は従来どおり(自動調光オフ): システムの明るさのまま、コントラストも変わらない
            assertEquals(-1f, f.activity.window.attributes.screenBrightness)
            assertEquals(0f, f.panel.contrastStrength, 0f)
            f.deliver(status(1))
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertTrue(f.onB)
        } finally { f.finish() }
        assertTrue("onPause で購読を止める", manager.listeners.isEmpty())
    }

    @Test fun lightSensorIsNotSubscribedWhenNothingNeedsIt() {
        val f = start { putBoolean("auto_brightness", false); putBoolean("dark_off", false); putBoolean("daidai_auto_switch", false) }
        try {
            assertTrue(manager.listeners.isEmpty())
        } finally { f.finish() }
    }

    @Test fun disabledAutoSwitchNeverSwitchesButStillUpdatesTheCard() {
        val f = start { putBoolean("daidai_auto_switch", false) }
        try {
            f.lux(180f)
            f.deliver(status(1))
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertFalse(f.onB)
            assertEquals("t-n1", f.daidaiView.uiState.notice?.title)
            assertEquals("見送り: 自動切替が無効", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    // --- 基準 ---

    @Test fun noticesThatArrivedWhileInTheBackgroundNeverSwitchOnResume() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1))
            f.controller.pause()           // 背面・設定画面へ
            f.controller.resume()
            f.lux(180f)                    // 戻ったあとの最初のセンサー値
            f.deliver(status(5, notice("while-away", age = 4)))
            idle(400)
            assertFalse("背面中に来た通知では切り替えない", f.onB)
            f.deliver(status(6, notice("fresh"), notice("while-away", age = 5)))
            idle(400)
            assertTrue(f.onB)
        } finally { f.finish() }
    }

    @Test fun luxIsUnknownAfterResumeUntilTheNextSensorValue() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1))
            f.controller.pause()
            f.controller.resume()
            f.deliver(status(5))
            f.deliver(status(6, notice("n1")))
            idle(400)
            assertFalse(f.onB)
            assertEquals("見送り: 照度が取れない", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    @Test fun legacyStatusWithoutDaidaiKeepsExistingBehavior() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(KioskStatus.parse(fixture("status-photo.json")))
            f.deliver(KioskStatus.parse(fixture("status-display.json")))
            idle(400)
            assertFalse(f.onB)
            assertEquals(AutoSwitchDiagnostics.NONE_TEXT, AutoSwitchDiagnostics.line)
            assertEquals("A の状態は従来どおり更新される", "geoframe-n100", f.panel.state.status?.host)
        } finally { f.finish() }
    }

    // --- B にいる・操作中 ---

    @Test fun whileOnBANewNoticeOnlyUpdatesTheCard() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1))
            f.deliver(status(2, notice("n1")))
            idle(400)
            assertTrue(f.onB)
            f.deliver(status(3, notice("n2"), notice("n1")))
            idle(400)
            assertTrue(f.onB)
            assertEquals("t-n2", f.daidaiView.uiState.notice?.title)
            assertEquals(1, f.daidaiView.uiState.moreNotices)
            assertEquals("更新のみ: すでにだいだい画面を表示中", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    @Test fun noticeDuringADragIsDeferredThenSwitchesOnceTheUserLetGoOfIt() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1))
            f.press(0.9f)
            for (x in listOf(0.85f, 0.8f, 0.75f)) { idle(20); f.touch(MotionEvent.ACTION_MOVE, x) }
            f.deliver(status(2, notice("n1")))
            assertEquals("保留: 操作中(終わってから判定し直す)", AutoSwitchDiagnostics.line)
            assertTrue("ドラッグ中は自動で動かさない", f.pager.position < 1f)
            // 元の位置へ戻して離す(ページは変わらない)
            for (x in listOf(0.8f, 0.9f)) { idle(20); f.touch(MotionEvent.ACTION_MOVE, x) }
            idle(20)
            f.touch(MotionEvent.ACTION_UP, 0.9f)
            idle(400)
            assertTrue("確定後に一度だけ再評価されて B へ", f.onB)
            assertEquals("切替: 明るい (180 lx)", AutoSwitchDiagnostics.line)
        } finally { f.finish() }
    }

    @Test fun intentExtrasStillCannotOpenB() {
        manager.addSensor(sensor)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val intent = android.content.Intent(context, PanelActivity::class.java)
            .putExtra("page", "B").putExtra("notice", "n1").putExtra("listen", true).putExtra("auto_switch", true)
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java, intent).setup())
        try {
            f.lux(180f)
            idle(1_000)
            assertFalse(f.onB)
            assertNull(f.daidaiView.uiState.notice)
        } finally { f.finish() }
    }
}
