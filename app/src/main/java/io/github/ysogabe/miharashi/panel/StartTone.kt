package io.github.ysogabe.miharashi.panel

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * マイクを開いた合図。[play]・[playFollow] は例外を投げない(鳴らせなくても録音は止めない。録音の表示は画面側にある)。
 * セッションのスレッドから呼ばれる(`DaidaiVoice` の `onMicOpened`)。
 */
fun interface StartSound {
    /** 会話の最初の往復の合図 */
    fun play()

    /**
     * 続きの往復の合図(段階 2a-P、S2-U10)。最初の往復と区別できる短い別の音にする。既定は [play] と同じ
     * (区別を持たない実装・試験用のフェイクのため)。
     */
    fun playFollow() = play()
}

/**
 * 開始音(設計書 §7、O-16)。`ToneGenerator`(アセット不要)で短い合図を、控えめな音量で鳴らす。
 * 生成・再生の失敗はすべて握りつぶす(鳴らせないことは録音を止める理由にしない)。どのスレッドから呼んでもよい。
 * 鳴らす間の録音は `VoiceSession` が [VoiceLimits.START_TONE_GUARD_MS] だけ捨てる(どちらの音もこの時間に収まる長さ)。
 *
 * - 最初の往復([play]): `TONE_PROP_BEEP`(単発のビープ)を [DURATION_MS] ms。
 * - 続きの往復([playFollow]、S2-U10): `TONE_PROP_ACK`(確認・受理を表す、ビープとは別系統の `PROP` 音)を [FOLLOW_DURATION_MS] ms。
 *   音の種類を変えて、聞いて「また聞いている」と分かるようにする。音量は同じ。**どちらの波形も、実機のスピーカーで聞き分けられるかは未確認**
 *   (`ToneGenerator` の音の定義は端末の実装にあり、このリポジトリでは測れない。2a-P の実機の確認で聞く。聞き分けられなければ [FOLLOW_TONE] を差し替える)。
 */
class ToneStartSound(
    private val volume: Int = VOLUME,
    private val releaseHandler: Handler = Handler(Looper.getMainLooper()),
) : StartSound {
    override fun play() = tone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)

    override fun playFollow() = tone(FOLLOW_TONE, FOLLOW_DURATION_MS)

    private fun tone(type: Int, durationMs: Int) {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            try {
                generator.startTone(type, durationMs)
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

        /** 続きの往復の音の種類(最初の往復の `TONE_PROP_BEEP` とは別) */
        const val FOLLOW_TONE = ToneGenerator.TONE_PROP_ACK

        /** 続きの往復の音の長さ。開始音の保護([VoiceLimits.START_TONE_GUARD_MS])より短い */
        const val FOLLOW_DURATION_MS = 200

        /** `ToneGenerator` の音量(0〜100)。スピーカーを驚かせない控えめな値 */
        const val VOLUME = 30
        private const val RELEASE_AFTER_MS = 400L
    }
}
