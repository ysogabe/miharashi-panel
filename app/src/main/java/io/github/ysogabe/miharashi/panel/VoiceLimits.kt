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

    // ---- 開始の合図(段 G。設計書(段階 2)§5.8)

    /** 挨拶(かチャイム)を鳴らし終えたあと、マイクを開いた直後に捨てる録音の長さ(残響の尾とマイクの立ち上がり)。ビープが無いので 400 ms は要らない */
    const val CUE_GUARD_MS = 200L

    /** 挨拶の再生の完了からマイクを開くまでの残響の待ち(続きの往復の [DaidaiVoice.ECHO_WAIT_MS] と同じ値) */
    const val CUE_ECHO_WAIT_MS = 300L

    /**
     * 挨拶の再生の期限 = 実データの長さ + この値。過ぎたら止めてチャイムへ。設計(§5.8.4)は 1.5 秒だが、[WavPlayer] は再生位置が進まない端末のために
     * 自分でも `track.play()` から長さ + 1.5 秒で打ち切る。こちらの期限は `play` を呼ぶ前から数えるので、同じ 1.5 秒だと、その端末では正常な挨拶も
     * 毎回「期限切れ」になってチャイムが続く。フォーカスの取得・トラックの作成・書き込みの分として 1 秒足す。最長の挨拶(5 秒)の失敗 + チャイムでも、
     * ホストの `idle_expire_s`(15 秒)に収まる(5 + 2.5 + 0.4 + 2.5 + 0.3 秒)。外へ出る脳では、このあとに表記の確認の待ち([BRAIN_NOTICE_DRAW_MS])が最大で加わっても、約 12.2 秒で収まる。
     */
    const val CUE_PLAYBACK_MARGIN_MS = 2_500L

    /** 挨拶 1 本の長さの上限(ホストの `idle_expire_s` 15 秒に収める) */
    const val CUE_MAX_MS = 5_000L

    /** 挨拶 1 本の WAV の大きさの上限(48 kHz・5 秒で約 480 KB) */
    const val CUE_MAX_BYTES = 1024 * 1024

    /** 挨拶の目録(JSON)の大きさの上限。今の目録は約 2.2 KB(`\u` の書き方でも) */
    const val CUE_MANIFEST_MAX_BYTES = 16 * 1024

    /** 挨拶の取り直しの間隔(取得に成功したあと)。失敗したあとは次の入場の前に取り直す */
    const val CUE_REFRESH_MS = 30 * 60_000L

    /** 挨拶の取得 1 回(目録か WAV 1 本)の全体の期限 */
    const val CUE_FETCH_TIMEOUT_MS = 10_000L

    // ---- 「外へ出る」表記の描画の確認(段階 2b-2。R15)

    /**
     * 脳が外へ出る区分(LAN・クラウド・不明)のとき、マイクを開く関所(`openMic`)に着いてから、画面が表記を描き終えた確認([VoiceSession.brainNoticeDrawn])を待つ上限。
     * 過ぎたら録音せず、`mic_unavailable` で終える(閉じる側に倒す)。画面は通常 1 フレーム(約 16 ms)で描く。合図の再生中に確認が届いていれば待たない。
     * 上限はホストの `idle_expire_s`(15 秒)に足しても収まる短さにする。
     */
    const val BRAIN_NOTICE_DRAW_MS = 1_500L

    // ---- ホスト値の既定と Panel の上限(秒)
    const val NO_SPEECH_DEFAULT_S = 10L
    const val NO_SPEECH_MAX_S = 10L

    /** 続きの往復の作成の本文に入れる `limits.no_speech_s`(段階 2a-P。設計書(段階 2)§5.3)。ホストは `min` を取る */
    const val FOLLOW_NO_SPEECH_S = 6L
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

    /** 会話のハンドル(ホストが発行する 128 ビットの乱数の URL-safe base64、22 文字)。完全一致で照合する(`matches`。末尾の改行を許さない) */
    val CONVERSATION_HANDLE_REGEX = Regex("^[A-Za-z0-9_-]{22}$")
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
