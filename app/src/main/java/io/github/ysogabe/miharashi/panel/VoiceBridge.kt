package io.github.ysogabe.miharashi.panel

/** セッション作成の `reason`(§5.2.1)。 */
enum class StartReason(val wire: String) { SWIPE("swipe"), NOTIFICATION("notification") }

/**
 * `POST /v1/voice/sessions` の本文(§5.2.1)。音声形式は [VoiceLimits] の固定値(`pcm_s16le` / 16 kHz / mono)。
 * `noticeId` は検証済み(`^[A-Za-z0-9_.:-]{1,64}$`)か null。トークンは持たない(T7 が `Authorization` に載せる)。
 */
data class SessionRequest(
    val clientId: String,
    val appVersion: String,
    val reason: StartReason,
    val noticeId: String?,
    /** Panel が使いたい上限の希望(`limits.no_speech_s`) */
    val noSpeechS: Long = VoiceLimits.NO_SPEECH_DEFAULT_S,
    /** 同 `limits.max_utterance_s` */
    val maxUtteranceS: Long = VoiceLimits.MAX_UTTERANCE_DEFAULT_S,
)

/**
 * 作成の応答(`201`)。`sessionId` はホストの文字列そのまま(形式の検証は [VoiceSession] が `^[A-Za-z0-9_-]{1,64}$` で行い、
 * 外れたら作成の失敗にする)。`toString` はセッション id を含めない。
 */
data class SessionInfo(val sessionId: String, val limits: HostLimits = HostLimits()) {
    override fun toString() = "SessionInfo(idLen=${sessionId.length}, limits=$limits)"
}

/** チャンクの応答の `state`。 */
enum class AckState { LISTENING, SPEECH, ENDED }

/** チャンクの応答(`200`)。`acceptedSeq` は受理した最大の連続 seq(累積)。 */
data class ChunkAck(val state: AckState, val acceptedSeq: Int)

/** SSE の `state` イベントの値。`listening` も届きうる(状態は後退しないので無視される)。 */
enum class HostState { LISTENING, SPEECH, WAITING }

/** `reply.audio`(§5.2.3)。 */
data class ReplyAudioInfo(val format: String, val sampleRate: Int, val bytes: Long, val durationMs: Long)

/**
 * SSE のイベント(§5.2.3)。`seq` はセッション内で単調増加(`id:` と一致)。
 * T7(`BridgeClient`)は未知のイベント名・壊れた JSON・形の違うもの・`seq` が整数でないものを**捨てて**から渡す。
 * テキストを持つ型の `toString` は本文を含めない(logcat・テスト失敗メッセージに残さない)。
 */
sealed class BridgeEvent {
    abstract val seq: Int

    data class State(override val seq: Int, val state: HostState) : BridgeEvent()

    data class Transcript(override val seq: Int, val text: String, val final: Boolean) : BridgeEvent() {
        override fun toString() = "Transcript(seq=$seq, chars=${text.length}, final=$final)"
    }

    data class Reply(
        override val seq: Int,
        val index: Int,
        val text: String,
        val audio: ReplyAudioInfo?,
        val audioError: String?,
        val final: Boolean,
        val avatarPose: String?,
    ) : BridgeEvent() {
        override fun toString() = "Reply(seq=$seq, index=$index, chars=${text.length}, audio=${audio != null}, final=$final)"
    }

    /** 終端のエラー。`code` はホストの文字列そのまま、`message` は診断の 1 行にだけ使う */
    data class Error(override val seq: Int, val code: String, val message: String?) : BridgeEvent() {
        override fun toString() = "Error(seq=$seq, code=${code.take(32)})"
    }

    data class Done(override val seq: Int) : BridgeEvent()
}

/** [BridgeException] の種類。 */
enum class BridgeFailure {
    /** 通信失敗・タイムアウト・中断(`abortInFlight`)。再送の対象 */
    NETWORK,

    /** `5xx`。`status` が 502 / 503 / 504 のときだけ再送の対象 */
    SERVER_ERROR,

    /** `409 ended`。終了済みのセッションへ新しい seq を送った。**エラーではなく正常な終了の通知** */
    ENDED,

    /** `409 out_of_order` */
    OUT_OF_ORDER,

    /** `409 seq_conflict` */
    SEQ_CONFLICT,

    /** `410 expired` */
    EXPIRED,

    /** `404` */
    NOT_FOUND,

    /** 上記以外の `4xx`(`401`・`400`・`429` など)。再送しない */
    CLIENT_ERROR,

    /** `503 asr_unavailable`(ホストが `code` で示したとき。`status` は 503) */
    ASR_UNAVAILABLE,

    /** 応答の形が違う・検証に落ちた(不正な JSON、WAV の検証失敗、上限超過など) */
    MALFORMED,
}

/**
 * [BridgeApi] の失敗。`kind` で区別し、`status` は HTTP ステータス(通信失敗は 0)、`hostCode` / `hostMessage` はエラー応答の
 * `error.code` / `error.message`(診断の 1 行にだけ使う。利用者向けの文言には使わない)。
 * `message`(例外メッセージ)にはホストの文字列を入れない。
 */
class BridgeException(
    val kind: BridgeFailure,
    val status: Int = 0,
    val hostCode: String? = null,
    val hostMessage: String? = null,
    cause: Throwable? = null,
) : Exception("bridge failure: $kind status=$status", cause) {
    /** 同じ seq を 1 回だけ再送してよい失敗: 通信失敗・タイムアウト・`502/503/504`(§5.2.2) */
    val isRetryable: Boolean get() = kind == BridgeFailure.NETWORK || status == 502 || status == 503 || status == 504
}

/**
 * ブリッジとの通信(設計書 §5.4)。**すべてブロッキング**で、[VoiceSession] のワーカー([SessionWorker])から呼ばれる。
 * 同時に複数のスレッドから呼ばれうる(SSE の `events` は 1 セッションの間ずっと 1 本占有し、その間に `sendAudio` も走る)。
 *
 * T7 が守ること:
 * - 失敗は [BridgeException] で投げる(通信失敗・タイムアウト・中断 = `NETWORK`)。それ以外の例外も VoiceSession は通信失敗として扱う。
 * - `createSession` は応答の `session_id` を**検証せず**そのまま返してよい(VoiceSession が検証し、不正なら作成の失敗にする)。
 * - `sendAudio`: 本文は生の PCM。`final` のときだけ `X-Chunk-Final: 1`。本文は 640〜16,000 バイト(最終チャンクも同じ長さに収めてくる)。
 *   `200` の応答の `state` が未知なら `MALFORMED`。`409` は本文の `error.code` で `ENDED` / `OUT_OF_ORDER` / `SEQ_CONFLICT` に分ける。
 * - `events`: **ブロッキング**。購読が終わる(EOF・切断・`abortInFlight`)まで戻らない。[BridgeEvent] を seq の順に `onEvent` へ渡す(別スレッドで呼んでよい)。
 *   `lastEventId` が null でなければ `Last-Event-ID` を送る(0〜999,999,999 のみ)。終端イベントを渡したあと EOF で戻ればよい(正常終了)。
 * - `fetchReplyAudio`: `index` は整数。検証済みの WAV を返す。10 MB・120 秒を超えるものは読みながら打ち切って `MALFORMED`。
 * - `cancel`: `DELETE`。`204` / `404` を成功とする。**`abortInFlight` で中断してはいけない**(停止の順序は abort → cancel なので、
 *   `cancel` は abort の後に始まるが、次のセッションの abort で潰れないよう、cancel 専用の接続は abort の対象外にする)。
 * - `abortInFlight`: 今通信中の接続を**すべて**(SSE を含む)切り、ブロック中の呼び出しを例外で戻らせる。ノンブロッキングで、どのスレッドからも呼べる。
 *   VoiceSession は 4 秒のチャンクのウォッチドッグでもこれを使うので、その副作用で SSE が切れる場合がある(VoiceSession は
 *   再接続の回数に数えずに張り直す)。
 * - 各呼び出し自身も期限を持つのが望ましい(防御)が、VoiceSession の側が期限(5 秒・4 秒・20 秒)を強制する。
 * - トークン・音声・認識テキストをログ・例外メッセージに出さない。
 */
interface BridgeApi {
    fun createSession(req: SessionRequest): SessionInfo
    fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean): ChunkAck
    fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit)
    fun fetchReplyAudio(id: String, index: Int): ByteArray
    fun cancel(id: String)
    fun abortInFlight()
}
