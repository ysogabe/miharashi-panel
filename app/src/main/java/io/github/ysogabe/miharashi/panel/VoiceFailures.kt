package io.github.ysogabe.miharashi.panel

/**
 * 失敗とホストのエラーコードを [VoiceErrorCode] に写す固定の方針(設計書 §5.5, §5.2.6)。
 *
 * ホストの `error.code`:
 * - 受け付けるのは `no_speech` / `asr_failed` / `timeout` / `daidai_unavailable` / `expired` / `busy` / `asr_unavailable` /
 *   `session_lost` / `reply_audio_failed`、別名の `tts_failed`(= `reply_audio_failed`)。
 * - Panel 自身が決めるコード(`not_configured` / `mic_*` / `host_unreachable`)をホストが送ってきても**採用しない**。
 * - **未知のコードは `null`**。ERROR にして `error = null`(画面は汎用の文言 `DaidaiText.GENERIC_ERROR`)、生のコード(整えたもの)は
 *   診断の 1 行にだけ載せる。別のコードに偽らない。
 *
 * HTTP の失敗([BridgeException]): 本文の `code` が上の表にあればそれ。なければ種類で:
 * `EXPIRED` → `expired`、`NOT_FOUND` / `OUT_OF_ORDER` / `SEQ_CONFLICT` → `session_lost`、`ASR_UNAVAILABLE` → `asr_unavailable`、
 * `NETWORK` / `SERVER_ERROR` / `CLIENT_ERROR` / `MALFORMED` と型のない例外 → `host_unreachable`。
 */
internal object VoiceFailures {
    private val HOST_ALIASES = mapOf("tts_failed" to VoiceErrorCode.REPLY_AUDIO_FAILED)
    private val PANEL_ONLY = setOf(
        VoiceErrorCode.NOT_CONFIGURED, VoiceErrorCode.MIC_PERMISSION, VoiceErrorCode.MIC_UNAVAILABLE,
        VoiceErrorCode.MIC_SILENT, VoiceErrorCode.HOST_UNREACHABLE,
    )

    /** ホストの `code` → 利用者向けコード。表にない・Panel 専用のコードは null */
    fun fromHostCode(code: String?): VoiceErrorCode? {
        if (code == null) return null
        HOST_ALIASES[code]?.let { return it }
        val known = VoiceErrorCode.fromCode(code) ?: return null
        return if (known in PANEL_ONLY) null else known
    }

    /** 通信の失敗 → コード。ここでは必ず [VoiceErrorCode] を返す(ホストの未知のコードは種類で決める) */
    fun fromException(e: Throwable): VoiceErrorCode {
        if (e !is BridgeException) return VoiceErrorCode.HOST_UNREACHABLE
        fromHostCode(e.hostCode)?.let { return it }
        return when (e.kind) {
            BridgeFailure.EXPIRED -> VoiceErrorCode.EXPIRED
            BridgeFailure.NOT_FOUND, BridgeFailure.OUT_OF_ORDER, BridgeFailure.SEQ_CONFLICT -> VoiceErrorCode.SESSION_LOST
            BridgeFailure.ASR_UNAVAILABLE -> VoiceErrorCode.ASR_UNAVAILABLE
            else -> VoiceErrorCode.HOST_UNREACHABLE
        }
    }

    /** 再送(同じ seq を 1 回)してよいか。型のない例外は通信失敗として扱う */
    fun isRetryable(e: Throwable): Boolean = e !is BridgeException || e.isRetryable

    /** 診断の 1 行。ホストの文字列は制御文字を除き、長さを切る */
    fun diagnostics(prefix: String, e: Throwable?): String {
        val tail = when (e) {
            is BridgeException -> buildString {
                append(e.kind).append(' ').append(e.status)
                e.hostCode?.let { append(' ').append(it) }
                e.hostMessage?.let { append(": ").append(it) }
            }
            null -> ""
            else -> e.javaClass.simpleName
        }
        return line(if (tail.isEmpty()) prefix else "$prefix $tail")
    }

    fun line(raw: String): String = DaidaiText.clean(raw, VoiceLimits.DIAGNOSTICS_MAX_CHARS, keepNewlines = false)

    /** `onEnded` の reason に使う、ホストの生のコードの整形(`[a-z0-9_]` だけ、32 文字まで。空なら "unknown") */
    fun reasonFromRaw(code: String?): String {
        val s = (code ?: "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32)
        return s.ifEmpty { "unknown" }
    }
}
