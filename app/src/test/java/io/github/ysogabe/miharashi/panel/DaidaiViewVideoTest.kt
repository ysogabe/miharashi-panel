package io.github.ysogabe.miharashi.panel

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [DaidaiView] が動画レイヤー(TextureView)を持っても、描画・タッチ・動画の結線が成り立つこと。偽のプレイヤーに差し替えて確かめる。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DaidaiViewVideoTest {
    private val players = FakeVideoPlayers()
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun newView(w: Int = 960, h: Int = 480) =
        DaidaiView(ctx, videoPlayers = players).also { it.layout(0, 0, w, h) }

    /** TextureView が SurfaceTexture を作った、と同じ呼び出し */
    private fun DaidaiView.surfaceAvailable() =
        videoLayer.surfaceTextureListener!!.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 720)

    private fun DaidaiView.surfaceDestroyed() =
        videoLayer.surfaceTextureListener!!.onSurfaceTextureDestroyed(SurfaceTexture(0))

    private fun pixels(view: DaidaiView): IntArray {
        val bitmap = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return IntArray(960 * 480).also { bitmap.getPixels(it, 0, 960, 0, 0, 960, 480); bitmap.recycle() }
    }

    @Test fun isAViewGroupWithOneVideoChildPlacedOnTheAvatar() {
        val v = newView()
        assertTrue(v is ViewGroup)
        assertEquals(1, v.childCount)
        val a = DaidaiLayout(960f, 480f, false).avatar
        assertEquals(Math.round(a.left), v.videoLayer.left)
        assertEquals(Math.round(a.top), v.videoLayer.top)
        assertEquals(Math.round(a.width), v.videoLayer.width)
        assertEquals(Math.round(a.height), v.videoLayer.height)
        assertFalse(v.videoLayer.isClickable)
        assertTrue(v.videoLayer.clipToOutline)
        assertNotNull(v.videoLayer.outlineProvider)
        // 別の大きさでも、静止画のアバターの位置に追従する
        v.layout(0, 0, 480, 240)
        val b = DaidaiLayout(480f, 240f, false).avatar
        assertEquals(Math.round(b.width), v.videoLayer.width)
        assertEquals(Math.round(b.left), v.videoLayer.left)
    }

    @Test fun theVideoLayerIsHiddenWithAlphaNotVisibility() {
        val v = newView()
        assertEquals(android.view.View.VISIBLE, v.videoLayer.visibility)
        assertEquals(0f, v.videoLayer.alpha, 0f)
        assertFalse(v.videoVisible)
    }

    @Test fun noVideoIsOpenedUnlessActive() {
        val v = newView()
        v.surfaceAvailable()
        v.render(DaidaiUiState(voice = VoiceState.LISTENING))
        assertTrue(players.created.isEmpty())
        v.videoActive = true
        assertEquals("daidai/daidai-listening.mp4", players.last.path)
        assertFalse(v.videoVisible)
        players.last.firstFrame()
        assertTrue(v.videoVisible)
        v.videoActive = false
        assertTrue(players.last.released)
        assertFalse(v.videoVisible)
    }

    @Test fun theVideoFollowsThePoseAndTheGuideShowsIdle() {
        val v = newView()
        v.videoActive = true
        v.surfaceAvailable()
        assertEquals("daidai/daidai-idle.mp4", players.last.path)
        v.render(DaidaiUiState(voice = VoiceState.WAITING))
        assertEquals("daidai/daidai-working.mp4", players.last.path)
        v.render(DaidaiUiState(voice = VoiceState.REPLYING))
        assertEquals("daidai/daidai-talking.mp4", players.last.path)
        // 待機中の通知カード: 通知のポーズ(milestone / making はそのまま)
        val notice = DaidaiNotice("n1", "おはよう", "今日は燃えるごみの日です", 3)
        v.render(DaidaiUiState(notice = notice, noticePose = "milestone"))
        assertEquals("daidai/daidai-milestone.mp4", players.last.path)
        v.render(DaidaiUiState(notice = notice, noticePose = "making"))
        assertEquals("daidai/daidai-making.mp4", players.last.path)
        // 未設定の案内は idle
        v.render(DaidaiUiState.forSettings(false))
        assertEquals("daidai/daidai-idle.mp4", players.last.path)
        val n = players.created.size
        v.render(DaidaiUiState.forSettings(false).copy(transcript = "同じポーズなら開き直さない"))
        assertEquals(n, players.created.size)
    }

    @Test fun surfaceDestroyAndDetachReleaseThePlayer() {
        val v = newView()
        v.videoActive = true
        v.surfaceAvailable()
        players.last.firstFrame()
        assertTrue(v.surfaceDestroyed())
        assertTrue(players.last.released)
        assertFalse(v.videoVisible)
        v.surfaceAvailable()
        assertFalse(players.last.released)
        assertFalse(v.videoVisible) // 開き直した直後は最初のフレームまで隠れる
    }

    @Test fun detachingTheViewReleasesThePlayer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = DaidaiView(activity, videoPlayers = players)
        activity.setContentView(v)
        v.videoActive = true
        v.surfaceAvailable()
        assertTrue(players.live.size == 1)
        activity.setContentView(android.view.View(activity)) // v が window から外れる
        assertEquals(0, players.live.size)
        assertTrue(players.last.released)
    }

    @Test fun videoLayerPaintFollowsContrastStrength() {
        val v = newView()
        assertNull(v.videoLayerPaint.colorFilter)
        v.contrastStrength = 0.5f
        assertNotNull(v.videoLayerPaint.colorFilter)
        v.contrastStrength = 0f
        assertNull(v.videoLayerPaint.colorFilter)
    }

    @Test fun drawingStaysStableWhileTheVideoLayerIsHidden() {
        // 最初のフレームの前(レイヤーは alpha 0)は、動画を動かしていても静止画の描画は動画なしと同じ。
        // (見えているときの TextureView は、ソフトウェアの Canvas では描かれないので、ここでは比べない)
        val v = newView()
        v.render(DaidaiUiState(voice = VoiceState.LISTENING))
        val before = pixels(v)
        v.videoActive = true
        v.surfaceAvailable()
        assertFalse(v.videoVisible)
        assertArrayEquals(before, pixels(v))
    }

    @Test fun touchesOverTheAvatarStillReachTheView() {
        val v = newView()
        var taps = 0
        v.onErrorTap = { taps++ }
        v.render(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH))
        val a = DaidaiLayout(960f, 480f, false).avatar
        fun send(action: Int, t: Long) {
            val e = MotionEvent.obtain(1000L, 1000L + t, action, a.centerX, a.centerY, 0)
            v.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_UP, 50)
        assertEquals(1, taps)
        // 動画が見えていても同じ
        v.videoActive = true
        v.surfaceAvailable()
        players.last.firstFrame()
        send(MotionEvent.ACTION_DOWN, 0)
        send(MotionEvent.ACTION_UP, 50)
        assertEquals(2, taps)
    }

    /** B の動画の結線を、実際の PagerLayout と PageController で再現する(PanelActivity と同じ式) */
    private inner class PagerRig {
        var now = 10_000L
        val view = newView()
        val controller = PageController(60_000)
        val pager = PagerLayout(ctx, android.view.View(ctx), view, controller, monotonicMs = { now })
        private var downTime = 0L

        init {
            controller.addListener(object : PageListener {
                override fun onBVisibleChanged(visible: Boolean) { view.videoActive = visible }
            })
            pager.measure(android.view.View.MeasureSpec.makeMeasureSpec(960, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY))
            pager.layout(0, 0, 960, 480)
            view.surfaceAvailable()
        }

        private fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(downTime, now, action, x, 240f, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }
        fun down(x: Float) { downTime = now; send(MotionEvent.ACTION_DOWN, x) }
        fun move(x: Float) { now += 16; send(MotionEvent.ACTION_MOVE, x) }
        fun up(x: Float) { now += 16; send(MotionEvent.ACTION_UP, x) }
        fun settle() { now += PagerLayout.ANIMATION_MS; pager.stepAnimation() }
        fun drag(x0: Float, x1: Float) { down(x0); for (i in 1..8) move(x0 + (x1 - x0) * i / 8) }
    }

    @Test fun noVideoIsOpenedWhileBIsDraggedButStillOutOfSight() {
        val r = PagerRig()
        // B へ入る: 見えるので開く
        r.drag(800f, 300f)
        assertTrue(r.controller.bVisible)
        assertEquals(android.view.View.VISIBLE, r.view.visibility)
        assertTrue(r.view.videoRunning)
        r.up(300f); r.settle()
        assertEquals(1, players.created.size)
        // A に戻る: 解放して止める
        r.drag(300f, 800f); r.up(800f); r.settle()
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(players.last.released)
        val opened = players.created.size
        // A を右へ引いて保持: PageController は bVisible だが B は INVISIBLE のまま。プレイヤーを開かない
        r.drag(100f, 600f)
        assertTrue("引いている間も bVisible", r.controller.bVisible)
        assertEquals(android.view.View.INVISIBLE, r.view.visibility)
        assertTrue(r.view.videoActive)
        assertFalse(r.view.videoRunning)
        assertEquals("見えない B の動画を開かない", opened, players.created.size)
        r.up(600f); r.settle()
        assertEquals(opened, players.created.size)
        // もう一度 B へ引いて見えたら開く(離す前でも)
        r.drag(800f, 600f)
        assertEquals(android.view.View.VISIBLE, r.view.visibility)
        assertTrue(r.view.videoRunning)
        assertEquals(opened + 1, players.created.size)
        // B が見えなくなったら(A へ戻り切ったら)また解放する
        r.drag(600f, 900f); r.up(900f); r.settle()
        assertEquals(android.view.View.INVISIBLE, r.view.visibility)
        assertTrue(players.last.released)
    }
}
