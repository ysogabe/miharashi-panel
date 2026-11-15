package io.github.ysogabe.miharashi.panel

import android.content.res.AssetManager
import android.media.MediaPlayer
import android.view.Surface

/**
 * `MediaPlayer` で assets の動画を、音なし・繰り返しで再生する [VideoPlayer]。
 * assets は `openFd` で渡すので、APK の中で mp4 が無圧縮であること(AGP の既定で無圧縮)。
 * コールバックは `MediaPlayer` を作ったスレッド(UI スレッド)で返る。
 */
class MediaPlayerVideoPlayer(private val assets: AssetManager) : VideoPlayer {
    private var mp: MediaPlayer? = null

    override fun open(path: String, surface: Surface, listener: VideoPlayer.Listener) {
        check(mp == null) { "already opened" }
        val p = MediaPlayer()
        mp = p
        try {
            p.setSurface(surface)
            p.setVolume(0f, 0f) // 素材に音声トラックは無いが、念のため
            p.isLooping = true
            p.setOnPreparedListener { it.start() }
            p.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) listener.onFirstFrame()
                true
            }
            p.setOnErrorListener { _, what, extra ->
                listener.onError(what, extra)
                true
            }
            assets.openFd(path).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.prepareAsync()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    override fun release() {
        val p = mp ?: return
        mp = null
        p.setOnPreparedListener(null)
        p.setOnInfoListener(null)
        p.setOnErrorListener(null)
        try { p.release() } catch (_: Exception) {}
    }
}
