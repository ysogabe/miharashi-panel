package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * R17(AC16)の Activity 結線: 未読のチップ(`PanelView.daidaiUnread`)、B を 2 秒以上見たあとの既読、
 * ack 対応のホストへの `POST /api/v1/daidai/ack`(panel のトークン)。通知は `onKioskState` へ流して再現する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PanelActivityUnreadTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
    private val manager get() = shadowOf(context.getSystemService(SensorManager::class.java))
    private val requests = LinkedBlockingQueue<RecordedRequest>()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        AutoSwitchDiagnostics.clear()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return MockResponse().setResponseCode(if (request.path == "/api/v1/daidai/ack") 204 else 404)
                }
            }
            start()
        }
    }

    @After fun tearDown() {
        AutoSwitchDiagnostics.clear()
        server.shutdown()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private inner class Fixture(val controller: org.robolectric.android.controller.ActivityController<PanelActivity>) {
        val activity: PanelActivity = controller.get()
        val pager = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PagerLayout
        val panel = pager.pageA as PanelView

        fun lux(v: Float) = manager.sendSensorEventToListeners(
            SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(v)).build(), sensor)

        fun deliver(status: KioskStatus) {
            val m = PanelActivity::class.java.getDeclaredMethod("onKioskState", PanelState::class.java).apply { isAccessible = true }
            m.invoke(activity, PanelState().withStatus(status, SystemClock.elapsedRealtime()))
        }

        val onB get() = pager.position == 1f
        fun finish() { controller.pause().stop().destroy() }
    }

    private fun start(): Fixture {
        manager.addSensor(sensor)
        // Robolectric 4.14 は未ロック時に任意の null コールバックを呼ぶため、スワイプロック状態で起動。
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit()
            .putString("url", server.url("/").toString().trimEnd('/'))
            .putString("token", PANEL_TOKEN)
            .putString("daidai_url", "http://192.168.0.132:8800")
            .putString("daidai_token", "bridge-token-0123456789")
            .commit()
        val f = Fixture(Robolectric.buildActivity(PanelActivity::class.java).setup())
        check(f.pager.width > 0) { "layout を持たない" }
        return f
    }

    private fun notice(id: String) = JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", 3)

    private fun status(rev: Long, ack: Boolean, unseen: Int, vararg ids: String): KioskStatus {
        val o = JSONObject(javaClass.getResource("/fixtures/status-daidai-empty.json")!!.readText())
        o.getJSONObject("daidai").put("rev", rev).put("ack", ack).put("unseen", unseen)
            .put("notices", JSONArray(ids.map { notice(it) }))
        return KioskStatus.parse(o.toString())
    }

    private fun acks(): List<RecordedRequest> = generateSequence { requests.poll(1, TimeUnit.SECONDS) }
        .filter { it.path == "/api/v1/daidai/ack" }.toList()

    @Test fun chipAppearsOnAThenClearsAfterBHasBeenShownForTwoSeconds() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1, ack = false, unseen = 0, "n0"))
            assertEquals("起動時に既にある通知は未読として残る(切り替えはしない)", 1, f.panel.daidaiUnread)
            assertFalse(f.onB)
            f.deliver(status(2, ack = false, unseen = 0, "n1", "n0"))
            idle(400)
            assertTrue("明るいので自動で B へ", f.onB)
            assertEquals(2, f.panel.daidaiUnread)
            idle(1_000)
            assertEquals("B を 2 秒見るまでは消えない", 2, f.panel.daidaiUnread)
            idle(2_500)
            assertEquals("B を見たので消える", 0, f.panel.daidaiUnread)
            assertTrue(acks().isEmpty())
        } finally { f.finish() }
    }

    @Test fun ackHostIsToldWithThePanelTokenOnlyAfterTheTwoSecondRule() {
        val f = start()
        try {
            f.lux(180f)
            f.deliver(status(1, ack = true, unseen = 0))
            f.deliver(status(2, ack = true, unseen = 1, "n1"))
            idle(400)
            assertTrue(f.onB)
            assertEquals("ack:true はホストの unseen", 1, f.panel.daidaiUnread)
            idle(1_000)
            assertTrue("2 秒前には送らない", acks().isEmpty())
            idle(2_500)
            val sent = acks()
            assertEquals(1, sent.size)
            assertEquals("POST", sent[0].method)
            assertEquals("""{"id":"n1"}""", sent[0].body.readUtf8())
            assertEquals("Bearer $PANEL_TOKEN", sent[0].getHeader("Authorization"))
            assertFalse("ブリッジのトークンは送らない", sent[0].headers.toString().contains("bridge-token"))
            assertEquals(0, f.panel.daidaiUnread)
            // ホストが既読を反映して unseen を 0 にした status が来ても 0 のまま
            f.deliver(status(3, ack = true, unseen = 0, "n1"))
            assertEquals(0, f.panel.daidaiUnread)
        } finally { f.finish() }
    }

    @Test fun noNoticesMeansNoChip() {
        val f = start()
        try {
            f.deliver(status(1, ack = false, unseen = 0))
            assertEquals("通知がなければ何も出さない", 0, f.panel.daidaiUnread)
        } finally { f.finish() }
    }

    private companion object {
        const val PANEL_TOKEN = "panel-token-0123456789"
    }
}
