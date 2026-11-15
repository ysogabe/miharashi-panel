package io.github.ysogabe.miharashi.panel

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 動画の再生制御([AvatarVideo])を、偽のプレイヤーで確かめる。 */
class FakeVideoPlayer : VideoPlayer {
    var path: String? = null
    var surface: Surface? = null
    var listener: VideoPlayer.Listener? = null
    var released = false
    var openFailure: Exception? = null

    override fun open(path: String, surface: Surface, listener: VideoPlayer.Listener) {
        openFailure?.let { throw it }
        this.path = path
        this.surface = surface
        this.listener = listener
    }

    override fun release() { released = true }

    fun firstFrame() = listener!!.onFirstFrame()
    fun error(what: Int = 1, extra: Int = -1004) = listener!!.onError(what, extra)
}

class FakeVideoPlayers : VideoPlayerFactory {
    val created = mutableListOf<FakeVideoPlayer>()
    var nextOpenFailure: Exception? = null

    override fun create(): VideoPlayer = FakeVideoPlayer().also {
        it.openFailure = nextOpenFailure
        nextOpenFailure = null
        created += it
    }

    val last get() = created.last()
    val live get() = created.filter { it.path != null && !it.released }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarVideoTest {
    private val players = FakeVideoPlayers()
    private val shown = mutableListOf<Boolean>()
    private val warnings = mutableListOf<String>()
    private lateinit var video: AvatarVideo
    private var layerShown = false

    @Before fun setUp() {
        video = AvatarVideo(players, { layerShown = it; shown += it }, { warnings += it })
    }

    private fun surface() = Surface(SurfaceTexture(0))

    /** 動かしてよく、surface もある状態にする */
    private fun ready(): Surface = surface().also { video.setActive(true); video.onSurfaceAvailable(it) }

    @Test fun nothingOpensUntilActiveAndSurfaceAreBothThere() {
        video.setPose("talking")
        assertTrue(players.created.isEmpty())
        video.setActive(true)
        assertTrue(players.created.isEmpty()) // surface がまだ
        video.onSurfaceAvailable(surface())
        assertEquals(1, players.created.size)
        assertEquals("daidai/daidai-talking.mp4", players.last.path)
        assertFalse(layerShown)
    }

    @Test fun layerIsHiddenUntilTheFirstFrameIsDrawn() {
        val s = ready()
        assertEquals("daidai/daidai-idle.mp4", players.last.path)
        assertSame(s, players.last.surface)
        assertFalse(layerShown)
        assertTrue(shown.isEmpty()) // 隠れたまま(余計に隠す呼び出しもしない)
        players.last.firstFrame()
        assertTrue(layerShown)
        players.last.firstFrame() // 2 度目は何もしない
        assertEquals(listOf(true), shown)
    }

    @Test fun aPoseChangeHidesAtOnceAndOpensTheNewPoseFromTheStart() {
        ready()
        val old = players.last
        old.firstFrame()
        assertTrue(layerShown)

        video.setPose("working")
        assertFalse(layerShown) // 古いポーズの最後の絵を見せない
        assertTrue(old.released)
        assertEquals(2, players.created.size)
        assertEquals("daidai/daidai-working.mp4", players.last.path)
        assertFalse(layerShown) // 新しい最初のフレームまで隠れたまま

        old.firstFrame() // 解放済みの古いプレイヤーのコールバックは無視する
        assertFalse(layerShown)
        players.last.firstFrame()
        assertTrue(layerShown)
        assertEquals(1, players.live.size)

        video.setPose("working") // 同じポーズなら開き直さない
        assertEquals(2, players.created.size)
        assertTrue(layerShown)
        video.setPose("milestone")
        assertEquals("daidai/daidai-milestone.mp4", players.last.path)
        video.setPose("dancing") // 語彙にない名前は idle
        assertEquals("daidai/daidai-idle.mp4", players.last.path)
    }

    @Test fun anErrorFallsBackToTheStillAndThatPoseIsNeverRetried() {
        ready()
        players.last.error(1, -1004)
        assertFalse(layerShown)
        assertTrue(players.last.released)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("idle") && warnings[0].contains("-1004"))
        assertTrue(video.hasFailed("idle"))
        assertFalse(video.isOpen)

        // 止めて動かし直しても、surface が作り直されても、別のポーズから戻っても、idle は開かない
        video.setActive(false)
        video.setActive(true)
        video.onSurfaceDestroyed()
        video.onSurfaceAvailable(surface())
        assertEquals(1, players.created.size)

        // 他のポーズは試す
        video.setPose("talking")
        assertEquals(2, players.created.size)
        assertEquals("daidai/daidai-talking.mp4", players.last.path)
        players.last.firstFrame()
        assertTrue(layerShown)
        video.setPose("idle") // 失敗したポーズへ戻っても開き直さない
        assertEquals(2, players.created.size)
        assertFalse(layerShown)
        assertTrue(players.last.released)
        assertEquals(1, warnings.size)
    }

    @Test fun anErrorAfterTheFirstFrameAlsoDropsToTheStill() {
        ready()
        players.last.firstFrame()
        players.last.error(100, 0)
        assertFalse(layerShown)
        assertTrue(video.hasFailed("idle"))
    }

    @Test fun aPlayerThatCannotBeOpenedCountsAsAFailure() {
        players.nextOpenFailure = java.io.FileNotFoundException("daidai/daidai-idle.mp4")
        ready()
        assertTrue(video.hasFailed("idle"))
        assertFalse(video.isOpen)
        assertFalse(layerShown)
        assertEquals(1, warnings.size)
        assertFalse(warnings[0].contains("daidai-idle.mp4")) // 例外のメッセージは出さない(型だけ)
        video.setPose("working")
        assertEquals("daidai/daidai-working.mp4", players.last.path)
    }

    @Test fun anErrorOfAnOldPlayerDoesNotFailTheNewPose() {
        ready()
        val old = players.last
        video.setPose("working")
        old.error()
        assertFalse(video.hasFailed("idle"))
        assertFalse(video.hasFailed("working"))
        assertTrue(warnings.isEmpty())
        assertTrue(video.isOpen)
    }

    @Test fun doesNotDecodeWhileInactiveAndResumesFromTheStartHidden() {
        ready()
        players.last.firstFrame()
        assertTrue(layerShown)

        video.setActive(false) // B が見えない・待機時計・背面
        assertTrue(players.last.released)
        assertEquals(0, players.live.size)
        assertFalse(layerShown)
        assertFalse(video.isOpen)

        video.setPose("listening") // 止まっている間にポーズが変わっても開かない
        assertEquals(1, players.created.size)

        video.setActive(true)
        assertEquals(2, players.created.size)
        assertEquals("daidai/daidai-listening.mp4", players.last.path)
        assertFalse(layerShown) // 再開も最初のフレームまで隠す
        players.last.firstFrame()
        assertTrue(layerShown)

        video.setActive(true) // 同じ値は何もしない
        assertEquals(2, players.created.size)
    }

    @Test fun releasesWhenTheSurfaceIsDestroyedAndReopensWhenItIsBack() {
        ready()
        players.last.firstFrame()
        video.setPose("working")
        players.last.firstFrame()

        video.onSurfaceDestroyed()
        assertEquals(0, players.live.size)
        assertFalse(layerShown)
        assertFalse(video.isOpen)

        video.setPose("talking") // surface が無い間は開かない
        assertEquals(2, players.created.size)

        val s2 = surface()
        video.onSurfaceAvailable(s2)
        assertEquals(3, players.created.size)
        assertEquals("daidai/daidai-talking.mp4", players.last.path)
        assertSame(s2, players.last.surface)
        assertFalse(layerShown)
        assertFalse(video.hasFailed("working"))
    }

    @Test fun detachReleasesThePlayer() {
        ready()
        players.last.firstFrame()
        video.release()
        assertTrue(players.last.released)
        assertFalse(layerShown)
        assertFalse(video.isOpen)
        video.release() // 2 回目も落ちない
        // surface が来るまで開かない
        video.setPose("working")
        assertEquals(1, players.created.size)
    }

    @Test fun aNewSurfaceReplacesTheOldPlayer() {
        ready()
        val old = players.last
        val s2 = surface()
        video.onSurfaceAvailable(s2)
        assertTrue(old.released)
        assertSame(s2, players.last.surface)
        assertEquals(1, players.live.size)
    }
}
