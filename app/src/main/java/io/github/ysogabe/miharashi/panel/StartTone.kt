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
 * 鳴らす間の録音は `VoiceSession` が [VoiceLimits.START_TONE_GUARD_MS] だけ捨てる。
 * **保護時間は「マイクを開いた時刻」から数える**が、`ToneGenerator` の生成と再生の開始はそのあと(セッションのスレッドで
 * [DaidaiVoice] の `onMicOpened` が呼ばれてから)なので、音の長さに使える余裕は保護時間より小さい。開始の遅れと音の尾に使える余裕は
 * 400 ms − 音の長さで、**この保証は実機で未確認**(実際の開始の遅れと残響の長さは 2a-P の実機の確認で測る)。そのため、続きの音も
 * 最初の往復の音より長くしない。
 *
 * - 最初の往復([play]): `TONE_PROP_BEEP`(単発のビープ)を [DURATION_MS] ms。
 * - 続きの往復([playFollow]、S2-U10): `TONE_DTMF_1`(DTMF の「1」。697 Hz と 1209 Hz の 2 周波の合成音。DTMF の周波数は規格で決まっていて、
 *   端末の実装で変わらない)を [FOLLOW_DURATION_MS] ms の 1 打だけ鳴らす。選んだ理由: ① `startTone` の長さで打ち切っても 1 打の音になる
 *   (`TONE_PROP_ACK` は 2 つ組の音で、中身が端末の実装に依るので、短く切ると最初の 1 打が `TONE_PROP_BEEP` と同じ音程になるおそれがある)、
 *   ② 単音のビープと違う、和音の音色で聞き分けやすい、③ [FOLLOW_DURATION_MS] は [DURATION_MS] 以下。音量は同じ。
 *   **どちらの音も、実機のスピーカーで聞き分けられるかは未確認**(2a-P の実機の確認で聞く。聞き分けられなければ [FOLLOW_TONE] を差し替える)。
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

        /** 続きの往復の音の種類(最初の往復の `TONE_PROP_BEEP` とは別。DTMF の「1」の 1 打) */
        const val FOLLOW_TONE = ToneGenerator.TONE_DTMF_1

        /** 続きの往復の音の長さ。最初の往復の [DURATION_MS] 以下(開始音の保護 [VoiceLimits.START_TONE_GUARD_MS] に収める余裕を、最初の往復と同じだけ残す) */
        const val FOLLOW_DURATION_MS = 100

        /** `ToneGenerator` の音量(0〜100)。スピーカーを驚かせない控えめな値 */
        const val VOLUME = 30
        private const val RELEASE_AFTER_MS = 400L
    }
}
