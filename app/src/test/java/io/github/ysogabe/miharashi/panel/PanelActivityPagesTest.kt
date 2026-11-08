package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.junit.Assert.*
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

private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

/** `PanelActivity` に載せた 2 画面(実際のティッカー・アニメーション・ライフサイクル)。時刻は Robolectric の時計。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PanelActivityPagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private class Fixture(val controller: ActivityController<PanelActivity>) {
        val activity: PanelActivity = controller.get()
        val pager = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PagerLayout
        val panel = pager.pageA as PanelView
        val daidai = pager.pageB as DaidaiView
        val sleeps = mutableListOf<Boolean>()

        init {
            val original = panel.onSleepChanged
            panel.onSleepChanged = { sleeps += it; original(it) }
        }

        private fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }
        private var down = 0L

        /** 画面の高さの中ほどで、`from` から `to`(幅に対する割合)へ横に動かす */
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
        fun finish() { controller.pause().stop().destroy() }
    }

    private fun start(idleSeconds: Int? = null, prefs: Context.() -> Unit = {}): Fixture {
        // Robolectric 4.14 は未ロック時に任意の null コールバックを呼ぶため、スワイプロック状態で起動。
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val edit = context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit()
        if (idleSeconds != null) edit.putInt("daidai_idle_seconds", idleSeconds)
        edit.commit()
        context.prefs()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        check(f.pager.width > 0) { "layout を持たない" }
        return f
    }

    @Test fun contentIsAPagerWithPanelFirstAndStartsOnA() {
        val f = start()
        try {
            assertSame(f.panel, f.pager.getChildAt(0))
            assertSame(f.daidai, f.pager.getChildAt(1))
            assertEquals(0f, f.pager.position, 0f)
            assertFalse(f.panel.idleSuspended)
        } finally { f.finish() }
    }

    @Test fun leftSwipeEntersBAndRightSwipeLeaves() {
        val f = start()
        try {
            f.toB()
            assertTrue("B の間は A の無操作判定を保留", f.panel.idleSuspended)
            f.swipe(0.1f, 0.8f); idle(400)
            assertEquals(0f, f.pager.position, 0f)
            assertFalse(f.panel.idleSuspended)
        } finally { f.finish() }
    }

    @Test fun clockNeverStartsWhileBIsShownAndIdleReturnDoesNotShowClockAtOnce() {
        // B の無操作 600 秒、A の時計 180 秒: 保留の解除時に A の古い時刻のままだと、戻った瞬間に時計が出る
        val f = start(idleSeconds = 600)
        try {
            f.panel.sleepTimeoutMs = 180_000
            f.toB()
            idle(599_000)
            assertEquals(1f, f.pager.position, 0f)
            assertTrue("B の表示中は時計に入らない: ${f.sleeps}", f.sleeps.isEmpty())
            idle(3_000)         // 600 秒で IDLE_RETURN、アニメーション完了
            assertEquals(0f, f.pager.position, 0f)
            idle(3_000)
            assertTrue("戻った瞬間に時計は出ない: ${f.sleeps}", f.sleeps.isEmpty())
            idle(170_000)       // 戻ってから約 176 秒
            assertTrue(f.sleeps.isEmpty())
            idle(10_000)        // sleep_minutes を超えたら時計に入る
            assertEquals(listOf(true), f.sleeps)
        } finally { f.finish() }
    }

    @Test fun defaultDaidaiIdleSecondsIsSixty() {
        val f = start()
        try {
            f.toB()
            idle(57_000)
            assertEquals(1f, f.pager.position, 0f)
            idle(5_000)
            assertEquals(0f, f.pager.position, 0f)
        } finally { f.finish() }
    }

    @Test fun touchingBKeepsItOpen() {
        val f = start(idleSeconds = 30)
        try {
            f.toB()
            idle(25_000)
            f.swipe(0.5f, 0.45f, steps = 1)      // 軽く触る(ページは変わらない)
            idle(400)
            idle(25_000)
            assertEquals(1f, f.pager.position, 0f)
            idle(10_000)
            assertEquals(0f, f.pager.position, 0f)
        } finally { f.finish() }
    }

    @Test fun clockShownThenTouchWithLongHorizontalDragStaysOnA() {
        val f = start()
        try {
            f.panel.sleepTimeoutMs = 1_000
            idle(2_000)
            assertEquals(listOf(true), f.sleeps)
            f.swipe(0.9f, 0.1f, steps = 10)
            idle(400)
            assertEquals("時計を消した指ではページは動かない", 0f, f.pager.position, 0f)
            assertEquals(listOf(true, false), f.sleeps)
            // 次の指は普通のスワイプ
            f.toB()
        } finally { f.finish() }
    }

    @Test fun pauseGoesBackToAAndResumeStartsFromA() {
        val f = start()
        try {
            f.toB()
            f.controller.pause()
            assertEquals(0f, f.pager.position, 0f)
            assertFalse(f.panel.idleSuspended)
            f.controller.resume()
            assertEquals(0f, f.pager.position, 0f)
            assertFalse(f.panel.idleSuspended)
            // 中断中のタイマーは動かない
            f.toB()
            f.controller.pause()
            idle(300_000)
            f.controller.resume()
            assertEquals(0f, f.pager.position, 0f)
        } finally { f.finish() }
    }

    @Test fun intentExtrasCannotSelectThePage() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val intent = Intent(context, PanelActivity::class.java)
            .putExtra("page", "B").putExtra("daidai", true).putExtra("page_index", 1).putExtra("listen", true)
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java, intent).setup())
        try {
            idle(1_000)
            assertEquals(0f, f.pager.position, 0f)
            assertFalse(f.panel.idleSuspended)
        } finally { f.finish() }
    }

    @Test fun contrastReachesBToo() {
        val sensor = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
        val manager = shadowOf(context.getSystemService(SensorManager::class.java))
        manager.addSensor(sensor)
        val f = start(prefs = {
            getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putBoolean("auto_brightness", true).commit()
        })
        try {
            manager.sendSensorEventToListeners(
                SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(500f)).build(), sensor)
            assertEquals(1f, f.panel.contrastStrength, 0f)
            assertEquals(1f, f.daidai.contrastStrength, 0f)
        } finally { f.finish() }
    }
}
