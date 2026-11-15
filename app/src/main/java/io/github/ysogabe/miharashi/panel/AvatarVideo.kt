package io.github.ysogabe.miharashi.panel

import android.util.Log
import android.view.Surface

/**
 * 動画を 1 本開いて、音なしで繰り返し再生するもの。本物は [MediaPlayerVideoPlayer]、テストは偽物に差し替える。
 * 呼び出しは UI スレッドから、コールバックも UI スレッドで返る。1 つのインスタンスで [open] は 1 回、[release] 後は使わない。
 */
interface VideoPlayer {
    /** `path`(assets 内)を `surface` に描く。準備ができたら先頭から再生する。開けなければ例外を投げる(非同期の失敗は [Listener.onError]) */
    fun open(path: String, surface: Surface, listener: Listener)

    /** デコーダーと `surface` への出力を止めて解放する。何度呼んでもよい */
    fun release()

    interface Listener {
        /** 最初のフレームが surface に描かれた(`MEDIA_INFO_VIDEO_RENDERING_START`) */
        fun onFirstFrame()

        /** 開けない・デコードできない。`what` / `extra` は `MediaPlayer.OnErrorListener` の値 */
        fun onError(what: Int, extra: Int)
    }
}

fun interface VideoPlayerFactory {
    fun create(): VideoPlayer
}

/**
 * アバターの動画の再生を決める(UI スレッド専用、Android の View には触れない)。
 * [DaidaiView] が surface の有無・ポーズ・動かしてよいか([setActive])を伝え、動画レイヤーの出し入れは `showLayer` で返す。
 *
 * - ポーズが変わったら、すぐレイヤーを隠して新しいポーズの動画を開き直し、最初のフレームが描かれてから見せる
 *   (古いポーズの最後の絵を見せない。隠している間は下の静止画が見える)。
 * - 開けない・デコードできないポーズは「失敗」として覚え、動いている間は開き直さない(静止画のまま)。他のポーズは試す。
 * - surface が無い・壊れたとき、動かしてはいけないとき(B が見えない・待機時計・背面)はプレイヤーを解放して隠す。
 *   戻ったら今のポーズで開き直す(解放するので、止めている間デコーダーを持たない)。
 */
class AvatarVideo(
    private val factory: VideoPlayerFactory,
    private val showLayer: (Boolean) -> Unit,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    private val pathOf: (String) -> String = AvatarAssets::videoPath,
) {
    private var pose = DaidaiPose.IDLE
    private var active = false
    private var surface: Surface? = null
    private var player: VideoPlayer? = null
    private var playingPose: String? = null
    private var shown = false
    private val failed = mutableSetOf<String>()

    /** 今 [pose] の動画を開いている(最初のフレームを待っているか、再生中) */
    val isOpen: Boolean get() = player != null

    /** 今のポーズが失敗として覚えられている */
    fun hasFailed(pose: String): Boolean = DaidaiPose.normalize(pose) in failed

    /** 表示するポーズ。語彙にない名前は `idle`。変わったらすぐ隠す */
    fun setPose(next: String) {
        val p = DaidaiPose.normalize(next)
        if (p == pose) return
        pose = p
        hide()
        reconcile()
    }

    /** 動かしてよいか(B が見えていて、前面で、待機時計でない)。false の間はデコードしない */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        reconcile()
    }

    fun onSurfaceAvailable(next: Surface) {
        surface?.takeIf { it !== next }?.let { dropPlayer(); it.release() }
        surface = next
        reconcile()
    }

    /** SurfaceTexture が壊れた: プレイヤーを先に解放し、surface も手放す */
    fun onSurfaceDestroyed() {
        dropPlayer()
        hide()
        surface?.release()
        surface = null
    }

    /** View が外れた。以後は surface が来るまで何もしない */
    fun release() = onSurfaceDestroyed()

    private fun hide() {
        if (shown) { shown = false; showLayer(false) }
    }

    private fun dropPlayer() {
        val p = player ?: return
        player = null
        playingPose = null
        p.release()
    }

    private fun reconcile() {
        val s = surface
        val want = active && s != null && pose !in failed
        if (!want) {
            dropPlayer()
            hide()
            return
        }
        if (player != null && playingPose == pose) return
        dropPlayer()
        hide()
        open(s!!)
    }

    private fun open(s: Surface) {
        val p = factory.create()
        val opening = pose
        player = p
        playingPose = opening
        val listener = object : VideoPlayer.Listener {
            override fun onFirstFrame() {
                if (player !== p || shown) return
                shown = true
                showLayer(true)
            }

            override fun onError(what: Int, extra: Int) {
                if (player !== p) return // 解放済みの古いプレイヤーのコールバック
                fail(opening, "what=$what extra=$extra")
            }
        }
        try {
            p.open(pathOf(opening), s, listener)
        } catch (e: Exception) {
            if (player === p) fail(opening, e.javaClass.simpleName)
        }
    }

    private fun fail(failedPose: String, detail: String) {
        failed += failedPose
        warn("avatar video failed: pose=$failedPose $detail")
        dropPlayer()
        hide()
    }

    private companion object {
        const val TAG = "AvatarVideo"
    }
}
