package io.github.ysogabe.miharashi.panel

/**
 * 音声セッションの定数(設計書 §5.2.6 の Panel 側の期限表、§5.8 の絶対上限)。ここに集約し、他の場所に数字を書かない。
 * ホストが返す値でこれらを超えることはできない([EffectiveLimits.resolve] が丸める)。
 */
object VoiceLimits {
    // ---- 音声の形式(セッション作成で宣言するもの)
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val AUDIO_ENCODING = "pcm_s16le"
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    // ---- §5.8 絶対上限
    /** マイクを開いている合計時間 / 1 セッション */
    const val MIC_MAX_MS = 45_000L

    /** セッションの総時間(開始から) */
    const val SESSION_MAX_MS = 120_000L

    /** 送信待ちキュー: 2 秒分。「64 KB」は 2 × 32,000 B/s = 64,000 B とする(65,536 ではない)。超えたら `host_unreachable` */
    const val QUEUE_MAX_BYTES = 2 * BYTES_PER_SECOND

    /** チャンク 1 回の長さ: 20〜500 ms(640〜16,000 バイト) */
    const val CHUNK_MIN_BYTES = 20 * BYTES_PER_SECOND / 1000
    const val CHUNK_MAX_BYTES = 500 * BYTES_PER_SECOND / 1000

    /** 返答テキストの最大(書記素を割らずに切り詰める) */
    const val REPLY_TEXT_MAX_CHARS = DaidaiText.MAX_REPLY

    /** 返答音声の最大(バイト、ミリ秒) */
    const val REPLY_AUDIO_MAX_BYTES = 10 * 1024 * 1024
    const val REPLY_AUDIO_MAX_MS = 120_000L

    /** SSE の再接続の最大(1 セッション合計) */
    const val SSE_MAX_RECONNECTS = 3

    // ---- §5.2.6 Panel 側の期限
    const val CREATE_TIMEOUT_MS = 5_000L
    const val CHUNK_TIMEOUT_MS = 4_000L

    /** 先頭の無音の判定窓: この長さの録音がすべて無音なら `mic_silent` */
    const val MIC_SILENT_WINDOW_MS = 3_000L

    /** 無音(RMS≒0)とみなす二乗平均平方根の上限(s16 のスケール)。マイクオフスイッチは厳密に 0 になる */
    const val SILENT_RMS_MAX = 2.0

    /** 発話前の待ち = `no_speech_s` + この値(最初のチャンクの受理から) */
    const val NO_SPEECH_MARGIN_MS = 3_000L

    /** 応答待ち = `reply_timeout_s` + この値(`ended` / `state=waiting` の受信から) */
    const val REPLY_TIMEOUT_MARGIN_MS = 5_000L
    const val REPLY_AUDIO_FETCH_TIMEOUT_MS = 20_000L

    /** 再生 = `duration_ms` + この値、ただし [REPLY_AUDIO_MAX_MS] まで */
    const val PLAYBACK_MARGIN_MS = 5_000L

    /** ERROR の表示時間(その後 IDLE) */
    const val ERROR_DISPLAY_MS = 8_000L

    /**
     * 開始音の保護時間。マイクを開いた直後から、この間に届いた録音は送らず捨てる。T8 が [VoiceSessionListener.onMicOpened] で
     * 鳴らす開始音(約 150 ms)とスピーカーの余韻を、無発話の判定・認識に混ぜないため。`mic_silent` の判定窓もこの後から数える。
     */
    const val START_TONE_GUARD_MS = 400L

    // ---- ホスト値の既定と Panel の上限(秒)
    const val NO_SPEECH_DEFAULT_S = 10L
    const val NO_SPEECH_MAX_S = 10L
    const val MAX_UTTERANCE_DEFAULT_S = 30L
    const val MAX_UTTERANCE_MAX_S = 30L
    const val REPLY_TIMEOUT_DEFAULT_S = 45L
    const val REPLY_TIMEOUT_MAX_S = 60L
    const val IDLE_EXPIRE_DEFAULT_S = 15L
    const val IDLE_EXPIRE_MAX_S = 15L
    const val RETAIN_DEFAULT_S = 60L
    const val RETAIN_MAX_S = 600L

    /** SSE の `id`(= イベント seq)として受け付ける範囲。`Last-Event-ID` は `^[0-9]{1,9}$` */
    const val MAX_EVENT_SEQ = 999_999_999

    /** 診断の 1 行の長さ */
    const val DIAGNOSTICS_MAX_CHARS = 120

    val SESSION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    val NOTICE_ID_REGEX = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    val POSE_REGEX = Regex("^[A-Za-z0-9_-]{1,32}$")
}

/**
 * ホストが返した `limits`(欠落は null、`Long` で受けるので巨大な値でも溢れない)。T7 は数値でない値・小数も null にする。
 */
data class HostLimits(
    val noSpeechS: Long? = null,
    val maxUtteranceS: Long? = null,
    val replyTimeoutS: Long? = null,
    val idleExpireS: Long? = null,
    val retainS: Long? = null,
)

/** Panel が実際に使う値(秒)。`min(Panel の上限, ホストの値)`。 */
data class EffectiveLimits(
    val noSpeechS: Long,
    val maxUtteranceS: Long,
    val replyTimeoutS: Long,
    val idleExpireS: Long,
    val retainS: Long,
) {
    companion object {
        val DEFAULTS = EffectiveLimits(
            VoiceLimits.NO_SPEECH_DEFAULT_S, VoiceLimits.MAX_UTTERANCE_DEFAULT_S, VoiceLimits.REPLY_TIMEOUT_DEFAULT_S,
            VoiceLimits.IDLE_EXPIRE_DEFAULT_S, VoiceLimits.RETAIN_DEFAULT_S,
        )

        /** 欠落・0 以下は既定値、それ以外は上限で丸める(§5.2.1)。 */
        fun resolve(host: HostLimits?): EffectiveLimits {
            fun pick(v: Long?, max: Long, default: Long) = if (v == null || v <= 0L) default else minOf(v, max)
            if (host == null) return DEFAULTS
            return EffectiveLimits(
                pick(host.noSpeechS, VoiceLimits.NO_SPEECH_MAX_S, VoiceLimits.NO_SPEECH_DEFAULT_S),
                pick(host.maxUtteranceS, VoiceLimits.MAX_UTTERANCE_MAX_S, VoiceLimits.MAX_UTTERANCE_DEFAULT_S),
                pick(host.replyTimeoutS, VoiceLimits.REPLY_TIMEOUT_MAX_S, VoiceLimits.REPLY_TIMEOUT_DEFAULT_S),
                pick(host.idleExpireS, VoiceLimits.IDLE_EXPIRE_MAX_S, VoiceLimits.IDLE_EXPIRE_DEFAULT_S),
                pick(host.retainS, VoiceLimits.RETAIN_MAX_S, VoiceLimits.RETAIN_DEFAULT_S),
            )
        }
    }
}
