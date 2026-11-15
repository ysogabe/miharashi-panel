package io.github.ysogabe.miharashi.panel

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** マイクを開いた合図。[play] は例外を投げない(鳴らせなくても録音は止めない。録音の表示は画面側にある)。 */
fun interface StartSound {
    fun play()
}

/**
 * 開始音(設計書 §7、O-16)。`ToneGenerator`(アセット不要)で約 [DURATION_MS] ms の短い合図を、控えめな音量で鳴らす。
 * 生成・再生の失敗はすべて握りつぶす(鳴らせないことは録音を止める理由にしない)。どのスレッドから呼んでもよい。
 * 鳴らす間の録音は `VoiceSession` が [VoiceLimits.START_TONE_GUARD_MS] だけ捨てる。
 */
class ToneStartSound(
    private val volume: Int = VOLUME,
    private val releaseHandler: Handler = Handler(Looper.getMainLooper()),
) : StartSound {
    override fun play() {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            try {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
            } catch (e: Exception) {
                generator.release()
                return
            }
            releaseHandler.postDelayed({ try { generator.release() } catch (_: Exception) { } }, RELEASE_AFTER_MS)
        } catch (_: Exception) {
            // 生成に失敗(音声ハードウェアが使えない など)。握りつぶす
        }
    }

    companion object {
        const val DURATION_MS = 150

        /** `ToneGenerator` の音量(0〜100)。スピーカーを驚かせない控えめな値 */
        const val VOLUME = 30
        private const val RELEASE_AFTER_MS = 400L
    }
}
