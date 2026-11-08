package io.github.ysogabe.miharashi.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * R17(AC16): 未読の手掛かり。ローカル既読規則(仮想時間)、ack の送信(MockWebServer)、画面 A のチップの描画(Robolectric)。
 * 結線は `DaidaiController`(`renderUnread` / `sendAck` / `tick`)、実際の Activity は `PanelActivityUnreadTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DaidaiUnreadTest {
    private val settings = DaidaiSettings(url = "http://192.168.0.132:8800", token = "bridge-token-0123456789")

    // --- 結線(仮想時間) ---

    private inner class Harness {
        var now = 1_000L
        val pages = PageController()
        val unread = mutableListOf<Int>()
        val acks = mutableListOf<String>()
        val controller = DaidaiController(
            pages, { settings }, {}, {}, { now },
            renderUnread = { unread += it }, sendAck = { acks += it },
        )

        fun start() = controller.start(PanelState(), "ep")
        fun feed(s: KioskStatus) = controller.onKioskState(PanelState().withStatus(s, now))
        val shown get() = unread.last()
        fun advance(ms: Long) { now += ms }

        fun enterB() {
            assertTrue(pages.beginDrag(now))
            pages.endDrag(Page.B, now)
            pages.animationEnded(now)
        }

        fun leaveB() {
            assertTrue(pages.beginDrag(now))
            pages.endDrag(Page.A, now)
            pages.animationEnded(now)
        }
    }

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun notice(id: String) = JSONObject().put("id", id).put("title", "t-$id").put("body", "b-$id").put("age_s", 3)

    /** `ack` が null なら項目を付けない(旧ホストの `daidai`)。 */
    private fun status(rev: Long, ack: Boolean?, unseen: Int?, vararg ids: String): KioskStatus {
        val d = JSONObject().put("rev", rev).put("notices", JSONArray(ids.map { notice(it) }))
        if (ack != null) d.put("ack", ack)
        if (unseen != null) d.put("unseen", unseen)
        return KioskStatus.parse(JSONObject(fixture("status-photo.json")).put("daidai", d).toString())
    }

    @Test fun localRuleMarksReadOnlyAfterTwoSecondsOnB() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = false, unseen = null, "n1", "n0"))
        assertEquals("未読 2 件(Panel が数える)", 2, h.shown)
        h.enterB()
        h.advance(1_999)
        h.controller.tick(h.now)
        assertEquals("2 秒未満では既読にしない", 2, h.shown)
        h.advance(1)
        h.controller.tick(h.now)
        assertEquals("2 秒ちょうどで、その時点の通知をすべて既読に", 0, h.shown)
        assertTrue("ack:false のホストには送らない", h.acks.isEmpty())
        h.leaveB()
        h.advance(10_000)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
    }

    @Test fun leavingBBeforeTwoSecondsKeepsItUnread() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = false, unseen = null, "n1"))
        h.enterB()
        h.advance(1_900)
        h.controller.tick(h.now)
        h.leaveB()
        h.advance(30_000)
        h.controller.tick(h.now)
        assertEquals("未読のまま", 1, h.shown)
        assertTrue(h.acks.isEmpty())
        // 次に B へ入ってから数え直す: 前回の 1.9 秒は持ち越さない
        h.enterB()
        h.advance(1_999)
        h.controller.tick(h.now)
        assertEquals(1, h.shown)
        h.advance(1)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
    }

    @Test fun newNoticeBecomesUnreadAgainAndIsReadAfterItsOwnTwoSeconds() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = false, unseen = null, "n1"))
        h.enterB()
        h.advance(2_000)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
        h.leaveB()
        h.advance(1_000)
        h.feed(status(2, ack = false, unseen = null, "n2", "n1"))
        assertEquals("A にいる間に来た通知は、また未読になる", 1, h.shown)
        // B を見ている最中に来た通知は、来た時点から 2 秒数える
        h.enterB()
        h.advance(2_000)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
        h.advance(1_500)
        h.feed(status(3, ack = false, unseen = null, "n3", "n2", "n1"))
        assertEquals(1, h.shown)
        h.advance(1_999)
        h.controller.tick(h.now)
        assertEquals(1, h.shown)
        h.advance(1)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
    }

    @Test fun repeatedStatusesWithoutNewNoticesDoNotDelayTheRead() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = false, unseen = null, "n1"))
        h.enterB()
        h.advance(1_500)
        h.feed(status(1, ack = false, unseen = null, "n1")) // 5 秒ごとのポーリング
        h.advance(500)
        h.controller.tick(h.now)
        assertEquals(0, h.shown)
    }

    @Test fun ackHostGetsAckOnceWithLatestIdAndUnseenComesFromTheHost() {
        val h = Harness()
        h.start()
        h.feed(status(5, ack = true, unseen = 3, "n3", "n2", "n1"))
        assertEquals("ack:true はホストの unseen", 3, h.shown)
        h.enterB()
        h.advance(1_999)
        h.controller.tick(h.now)
        assertTrue(h.acks.isEmpty())
        assertEquals(3, h.shown)
        h.advance(1)
        h.controller.tick(h.now)
        assertEquals("最新の通知の id を 1 回だけ送る", listOf("n3"), h.acks)
        assertEquals("ホストの次の status を待たずに手掛かりは消す", 0, h.shown)
        h.advance(5_000)
        h.controller.tick(h.now)
        assertEquals("B にいる間に何度 tick しても再送しない", listOf("n3"), h.acks)
        h.feed(status(6, ack = true, unseen = 0, "n3", "n2", "n1")) // ホストが既読を反映
        assertEquals(0, h.shown)
        h.leaveB()
        h.feed(status(7, ack = true, unseen = 1, "n4", "n3", "n2", "n1"))
        assertEquals("新しい通知でホストの unseen が増えたら、また出る", 1, h.shown)
    }

    @Test fun noAckWhenThereIsNothingUnreadOrTheHostDoesNotManageReads() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = true, unseen = 0, "n1"))
        h.enterB()
        h.advance(3_000)
        h.controller.tick(h.now)
        assertTrue("未読 0 なら ack しない", h.acks.isEmpty())

        val old = Harness()
        old.start()
        old.feed(status(1, ack = null, unseen = 2, "n1")) // ack 項目なし = false
        assertEquals(1, old.shown)
        old.enterB()
        old.advance(3_000)
        old.controller.tick(old.now)
        assertEquals(0, old.shown)
        assertTrue("ack 項目なしのホストには送らない", old.acks.isEmpty())
    }

    @Test fun legacyStatusWithoutDaidaiShowsNothingAndNeverAcks() {
        val h = Harness()
        h.start()
        h.feed(KioskStatus.parse(fixture("status-photo.json")))
        assertEquals(0, h.shown)
        h.enterB()
        h.advance(5_000)
        h.controller.tick(h.now)
        assertTrue(h.acks.isEmpty())
        assertEquals(0, h.shown)
    }

    @Test fun readStateSurvivesBackgroundingButAReadIsNotCarriedByAPendingTimer() {
        val h = Harness()
        h.start()
        h.feed(status(1, ack = false, unseen = null, "n1"))
        h.enterB()
        h.advance(1_000)
        h.controller.stop() // 背面
        h.advance(60_000)
        h.controller.tick(h.now)
        assertEquals("背面の間は既読にならない", 1, h.shown)
        h.pages.onPause(h.now)
        h.pages.onResume(h.now)
        h.start()
        assertEquals("前面に戻っても未読(基準の取り直しは表示用のデータと既読を残す)", 1, h.shown)
    }

    // --- 画面 A のチップの描画 ---

    private fun panel(): PanelView {
        val v = PanelView(RuntimeEnvironment.getApplication(), { _, _ -> }, {}, monotonicMs = { 0L }, epochMs = { 0L })
        v.layout(0, 0, 960, 480)
        v.configured = true
        return v
    }

    private fun pixels(v: PanelView): IntArray {
        val bitmap = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return IntArray(960 * 480).also { bitmap.getPixels(it, 0, 960, 0, 0, 960, 480); bitmap.recycle() }
    }

    private fun differing(a: IntArray, b: IntArray): List<Pair<Int, Int>> =
        a.indices.filter { a[it] != b[it] }.map { it % 960 to it / 960 }

    private fun inChip(points: List<Pair<Int, Int>>): Boolean {
        val chip = PanelLayout(960f, 480f).daidaiChip
        return points.all { (x, y) -> x >= chip.left && x < chip.right && y >= chip.top && y < chip.bottom }
    }

    @Test fun chipIsDrawnOnlyInsideItsBoxAndNotWhenThereIsNothingUnread() {
        val v = panel()
        val none = pixels(v)
        v.daidaiUnread = 1
        val one = pixels(v)
        val diff = differing(none, one)
        assertTrue("未読があれば描く", diff.size > 100)
        assertTrue("チップの箱の外は 1 画素も変えない", inChip(diff))
        v.daidaiUnread = 0
        assertTrue("0 に戻せば元の絵", none.contentEquals(pixels(v)))
        v.daidaiUnread = -3
        assertTrue("負の値は 0 と同じ", none.contentEquals(pixels(v)))
    }

    @Test fun chipShowsTheCountAndCapsAtNinePlus() {
        val v = panel()
        v.daidaiUnread = 1
        val one = pixels(v)
        v.daidaiUnread = 2
        val two = pixels(v)
        v.daidaiUnread = 9
        val nine = pixels(v)
        v.daidaiUnread = 10
        val ten = pixels(v)
        v.daidaiUnread = 250
        val many = pixels(v)
        assertTrue("件数が違えば絵が違う", differing(one, two).isNotEmpty())
        assertTrue(inChip(differing(one, two)))
        assertTrue("9 と 10 は違う(10 は 9+)", differing(nine, ten).isNotEmpty())
        assertTrue("10 以上は同じ絵(9+)で、幅が変わらない", ten.contentEquals(many))
    }

    @Test fun chipFollowsTheAmbientContrastStrength() {
        val v = panel()
        v.daidaiUnread = 1
        v.contrastStrength = AmbientContrast.strength(10f, true)
        val dim = pixels(v)
        v.contrastStrength = AmbientContrast.strength(250f, true)
        val bright = pixels(v)
        val chip = PanelLayout(960f, 480f).daidaiChip
        val inside = differing(dim, bright).count { (x, y) -> x >= chip.left && x < chip.right && y >= chip.top && y < chip.bottom }
        assertTrue("明るい場所ではチップの色もコントラスト補正に従う($inside 画素)", inside > 100)
    }

    @Test fun chipIsNotDrawnWhileTheIdleClockIsShown() {
        var now = 0L
        val v = PanelView(RuntimeEnvironment.getApplication(), { _, _ -> }, {}, monotonicMs = { now }, epochMs = { 0L })
        v.layout(0, 0, 960, 480)
        v.configured = true
        v.sleepTimeoutMs = 1L
        now = 30_000L
        PanelView::class.java.getDeclaredMethod("updateSleep").apply { isAccessible = true }.invoke(v)
        v.daidaiUnread = 0
        val clock = pixels(v)
        v.daidaiUnread = 5
        assertTrue("時計の絵に未読チップは混ざらない", clock.contentEquals(pixels(v)))
    }

    @Test fun layoutRectanglesOfAreUnchangedByTheChip() {
        // 画面 A の既存の矩形(960×480 基準)。チップを足しても 1 つも動かない
        val l = PanelLayout(960f, 480f)
        assertEquals(Box(224f, 268f, 376f, 456f), l.mainKeys[0].box)
        assertEquals(Box(384f, 268f, 536f, 456f), l.mainKeys[1].box)
        assertEquals(Box(544f, 268f, 696f, 456f), l.mainKeys[2].box)
        assertEquals(Box(736f, 72f, 944f, 192f), l.sideKeys[0].box)
        assertEquals(Box(736f, 204f, 944f, 324f), l.sideKeys[1].box)
        assertEquals(Box(736f, 336f, 944f, 456f), l.sideKeys[2].box)
        assertEquals(Box(8f, 148f, 192f, 224f), l.displayKeys[0].box)
        assertEquals(Box(8f, 232f, 192f, 308f), l.displayKeys[1].box)
        assertEquals(Box(8f, 316f, 192f, 392f), l.displayKeys[2].box)
        assertEquals(Box(8f, 420f, 192f, 472f), l.settings.box)
        assertEquals(Box(8f, 64f, 192f, 140f), l.monitorStatus)
        assertEquals(Box(216f, 64f, 704f, 252f), l.screenCard)
        assertEquals(Box(216f, 196f, 704f, 252f), l.rotationBand)
        assertEquals(Box(216f, 64f, 704f, 236f), l.menuArea)
        assertEquals(Box(0f, 0f, 960f, 56f), l.header)
        assertEquals("ボタンは 10 個のまま(チップはボタンではない)", 10, l.buttons.size)
        assertNull("チップの上のタップは何もしない", l.hit(l.daidaiChip.centerX, l.daidaiChip.centerY))
    }

    @Test fun chipSitsInTheHeaderClearOfEverythingElse() {
        for ((w, h) in listOf(960f to 480f, 1920f to 1080f, 1024f to 600f)) {
            val l = PanelLayout(w, h)
            val c = l.daidaiChip
            val unit = l.scale
            assertTrue("ヘッダーの中", c.left >= l.header.left && c.right <= l.header.right && c.top >= l.header.top && c.bottom <= l.header.bottom)
            // 基準座標に戻して、ヘッダーの既存の要素(WIFI の文字は x=624 から約 60、時刻は右端から約 90)と比べる
            val baseLeft = (c.left - (w - 960f * unit) / 2) / unit
            val baseRight = (c.right - (w - 960f * unit) / 2) / unit
            assertTrue("WIFI の表示より右($baseLeft)", baseLeft >= 690f)
            assertTrue("時刻より左($baseRight)", baseRight <= 850f)
            for (b in l.buttons) {
                val overlap = c.left < b.box.right && b.box.left < c.right && c.top < b.box.bottom && b.box.top < c.bottom
                assertFalse(b.id, overlap)
            }
        }
        // ページ点(PagerLayout が画面下端に描く)とも離れている
        assertTrue(PanelLayout(960f, 480f).daidaiChip.bottom < 440f)
    }

    // --- ack の送信(MockWebServer) ---

    private lateinit var server: MockWebServer
    private val requests = java.util.concurrent.LinkedBlockingQueue<RecordedRequest>()

    @Before fun startServer() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return when (request.path) {
                        "/api/v1/daidai/ack" -> MockResponse().setResponseCode(if (failAck) 500 else 204)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @After fun stopServer() = server.shutdown()

    @Volatile private var failAck = false

    /** `start()` すると状態の取得や SSE の通信も走るので、送信用のスレッドだけを使えるように `running` を立てる。 */
    private fun runningSync(): KioskSync {
        val sync = KioskSync(KioskClient(server.url("/").toString(), PANEL_TOKEN), PanelState()) {}
        KioskSync::class.java.getDeclaredField("running").apply { isAccessible = true }.set(sync, true)
        return sync
    }

    @Test fun ackIsPostedOnTheSenderThreadWithThePanelTokenNotTheBridgeToken() {
        val sync = runningSync()
        try {
            sync.ackDaidai("n-1")
            val req = requests.poll(3, TimeUnit.SECONDS)!!
            assertEquals("POST", req.method)
            assertEquals("/api/v1/daidai/ack", req.path)
            assertEquals("""{"id":"n-1"}""", req.body.readUtf8())
            assertEquals("Bearer $PANEL_TOKEN", req.getHeader("Authorization"))
            assertFalse("ブリッジのトークンは付けない", req.headers.toString().contains(settings.token))
        } finally {
            (KioskSync::class.java.getDeclaredField("sender").apply { isAccessible = true }.get(sync) as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test fun ackFailureNeverEscapesAndNothingIsRetried() {
        failAck = true
        val sync = runningSync()
        try {
            sync.ackDaidai("n-1") // 500 でも例外は出ない
            assertEquals("n-1", JSONObject(requests.poll(3, TimeUnit.SECONDS)!!.body.readUtf8()).getString("id"))
            sync.ackDaidai("bad id") // 不正な id は送らず、例外も出さない
            sync.ackDaidai("n-2")
            val next = requests.poll(3, TimeUnit.SECONDS)!!
            assertEquals("再送しない・不正な id は送らない", "n-2", JSONObject(next.body.readUtf8()).getString("id"))
        } finally {
            (KioskSync::class.java.getDeclaredField("sender").apply { isAccessible = true }.get(sync) as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test fun ackAfterStopIsIgnored() {
        val sync = KioskSync(KioskClient(server.url("/").toString(), PANEL_TOKEN), PanelState()) {}
        sync.ackDaidai("n-1") // 始めていない
        sync.stop()
        sync.ackDaidai("n-1") // 止めたあと(送信用のスレッドは閉じている)でも例外は出ない
        assertNull(requests.poll(300, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val PANEL_TOKEN = "panel-token-0123456789"
    }
}
