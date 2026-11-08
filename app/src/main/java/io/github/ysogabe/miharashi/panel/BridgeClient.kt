package io.github.ysogabe.miharashi.panel

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.Collections
import java.util.IdentityHashMap

/**
 * ブリッジ(ホスト側の音声サーバー)との通信の実体(設計書 §5.2, §5.4, §7)。`HttpURLConnection` と `org.json` だけで、Android に依存しない。
 * 呼び出しはすべてブロックする([VoiceSession] のワーカーから呼ばれる)。
 *
 * ## 守る規則(§5.2.0, §7 接続先の誘導)
 * - **URL は Panel が組む**: 検証済みの `daidai_url`([Settings.validateDaidaiUrl])に固定のパスを足す。ホストの応答に含まれるパス・URL は
 *   **読まない・使わない**(`session_id` は `^[A-Za-z0-9_-]{1,64}$`、`index` は 0〜[MAX_REPLY_INDEX] の整数だけがパスに入る)。
 * - **リダイレクト(3xx)は追従せずエラー**(`CLIENT_ERROR`)。別のホストへトークンを送らない。
 * - 送る資格情報は `Authorization: Bearer <ブリッジ専用トークン>` だけ。**panel のトークンはこのクラスに渡されない**(AC20)。
 *   URL かトークンが不正(未設定・トークンに使えない文字)なら、通信せず `CLIENT_ERROR`。
 * - 応答本文は JSON もエラーも **4 KB まで**。超えたら JSON は `MALFORMED`、エラーは本文を使わずステータスだけで分類する。
 * - トークン・認識テキスト・返答・音声をログ・例外メッセージ・`toString` に出さない。ホストの `error.message` は
 *   [BridgeException.hostMessage] に(整形して 200 文字まで)。診断の 1 行にだけ使う。
 *
 * ## 中断([abortInFlight])
 * 通信中のすべての接続(SSE を含む)を切る。どのスレッドからでも呼べる。**クライアントは壊れず**、次の呼び出しは普通に通信できる
 * (チャンクのウォッチドッグは abort のあとに同じ seq を再送し、停止は abort のあとに [cancel] を送る)。
 * 呼び出しが始まっていても接続を登録する前に abort が来たときは、登録の時点で世代の違いに気づいて中断する(取りこぼしを防ぐ)。
 * [cancel] の接続は中断の対象外で、abort の後でも送れる。
 *
 * ## 期限
 * 作成・チャンク・返答音声・取消は、それぞれ自分で全体の期限(接続前から数える。[BridgeHttp.withDeadline])を持つ。既定値は
 * [VoiceSession] のウォッチドッグ(作成 5 秒・チャンク 4 秒・返答音声 20 秒)より [DEADLINE_MARGIN_MS] 長く、通常は呼び出し側が先に効く
 * (これは防御で、強制するのは呼び出し側)。SSE([events])は長く続くので全体の期限を持たず、読み取りのタイムアウト 40 秒
 * (ホストの keepalive は 15 秒ごと)で切る。
 */
class BridgeClient(
    baseUrl: String,
    token: String,
    private val connectTimeoutMs: Int = BridgeHttp.CONNECT_TIMEOUT_MS,
    private val createTimeoutMs: Long = VoiceLimits.CREATE_TIMEOUT_MS + DEADLINE_MARGIN_MS,
    private val chunkTimeoutMs: Long = VoiceLimits.CHUNK_TIMEOUT_MS + DEADLINE_MARGIN_MS,
    private val replyAudioTimeoutMs: Long = VoiceLimits.REPLY_AUDIO_FETCH_TIMEOUT_MS + DEADLINE_MARGIN_MS,
    private val cancelTimeoutMs: Long = CANCEL_TIMEOUT_MS,
    private val eventsReadTimeoutMs: Int = EVENTS_READ_TIMEOUT_MS,
) : BridgeApi {
    private val base = baseUrl.trim()
    private val bearer = token.trim()
    private val configured = base.isNotEmpty() && bearer.isNotEmpty() && Settings.validateDaidaiUrl(base) == null

    // ---- 中断(abort)の管理
    private val lock = Any()
    private var epoch = 0
    private val active: MutableSet<HttpURLConnection> = Collections.newSetFromMap(IdentityHashMap())

    override fun toString() = "BridgeClient(configured=$configured)"

    /** 通信中の接続をすべて切る。ノンブロッキング・冪等。これから始まる呼び出しは巻き込まない(世代で見分ける)。 */
    override fun abortInFlight() {
        val conns = synchronized(lock) {
            epoch++
            active.toList().also { active.clear() }
        }
        for (c in conns) {
            try {
                c.disconnect()
            } catch (_: RuntimeException) {
            }
        }
    }

    // =========================================================================================
    // セッションの作成(§5.2.1)

    override fun createSession(req: SessionRequest): SessionInfo {
        val body = JSONObject()
            .put("client", JSONObject().put("id", req.clientId).put("app_version", req.appVersion))
            .put("reason", req.reason.wire)
            .put("notice_id", req.noticeId ?: JSONObject.NULL)
            .put(
                "audio",
                JSONObject().put("encoding", VoiceLimits.AUDIO_ENCODING).put("sample_rate", VoiceLimits.SAMPLE_RATE)
                    .put("channels", VoiceLimits.CHANNELS),
            )
            .put("limits", JSONObject().put("no_speech_s", req.noSpeechS).put("max_utterance_s", req.maxUtteranceS))
            .toString().toByteArray(Charsets.UTF_8)
        return exchange(
            "POST", "/v1/voice/sessions", ACCEPT_JSON, abortable = true, totalMs = createTimeoutMs, readTimeoutMs = readMs(createTimeoutMs),
            body = body, contentType = "application/json",
        ) { c ->
            val code = c.responseCode
            if (code != 201) failStatus(c, code, forChunk = false)
            val o = readJson(c, code)
            // 応答のほかの項目(URL・パスのように見えるもの)は読まない。使うのは session_id と limits だけ
            val id = (o.opt("session_id") as? String)?.takeIf { VoiceLimits.SESSION_ID_REGEX.matches(it) }
                ?: fail(BridgeFailure.MALFORMED, code)
            SessionInfo(id, parseLimits(o.opt("limits")))
        }
    }

    private fun parseLimits(v: Any?): HostLimits {
        val o = v as? JSONObject ?: return HostLimits()
        return HostLimits(
            noSpeechS = o.longOrNull("no_speech_s"),
            maxUtteranceS = o.longOrNull("max_utterance_s"),
            replyTimeoutS = o.longOrNull("reply_timeout_s"),
            idleExpireS = o.longOrNull("idle_expire_s"),
            retainS = o.longOrNull("retain_s"),
        )
    }

    // =========================================================================================
    // 音声チャンク(§5.2.2)

    override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean): ChunkAck {
        requireSessionId(id)
        if (seq < 0) throw BridgeException(BridgeFailure.MALFORMED)
        val headers = buildList {
            add("X-Chunk-Seq" to seq.toString())
            if (final) add("X-Chunk-Final" to "1")
        }
        return exchange(
            "POST", "/v1/voice/sessions/$id/audio", ACCEPT_JSON, abortable = true, totalMs = chunkTimeoutMs, readTimeoutMs = readMs(chunkTimeoutMs),
            headers = headers, body = pcm, contentType = "application/octet-stream",
        ) { c ->
            val code = c.responseCode
            if (code != 200) failStatus(c, code, forChunk = true)
            val o = readJson(c, code)
            val state = when (o.opt("state") as? String) {
                "listening" -> AckState.LISTENING
                "speech" -> AckState.SPEECH
                "ended" -> AckState.ENDED
                else -> fail(BridgeFailure.MALFORMED, code)
            }
            val accepted = o.intOrNull("accepted_seq")?.takeIf { it in 0..VoiceLimits.MAX_EVENT_SEQ } ?: fail(BridgeFailure.MALFORMED, code)
            ChunkAck(state, accepted)
        }
    }

    // =========================================================================================
    // イベント(SSE、§5.2.3)

    override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {
        requireSessionId(id)
        val headers = buildList {
            add("Cache-Control" to "no-cache")
            // Last-Event-ID は ^[0-9]{1,9}$ のときだけ送る(Int の十進表記は 0〜999,999,999 で、これに当てはまる)
            if (lastEventId != null && lastEventId in 0..VoiceLimits.MAX_EVENT_SEQ) add("Last-Event-ID" to lastEventId.toString())
        }
        exchange(
            "GET", "/v1/voice/sessions/$id/events", ACCEPT_SSE, abortable = true, totalMs = null, readTimeoutMs = eventsReadTimeoutMs,
            headers = headers,
        ) { c ->
            val code = c.responseCode
            if (code != 200) failStatus(c, code, forChunk = false)
            if (!hasMediaType(c.contentType, "text/event-stream")) fail(BridgeFailure.MALFORMED, code)
            val parser = SseParser()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                // 行・イベントの上限を超えたら SseLimitException(IOException)。接続を切って NETWORK として投げる
                val lines = BoundedLineReader(r, SseParser.MAX_LINE_BYTES)
                while (true) {
                    val line = lines.readLine() ?: break // 終端なしの EOF は普通に戻る(VoiceSession が「途切れ」として再接続する)
                    val ev = parser.next(line) ?: continue
                    val event = toEvent(id, ev) ?: continue
                    onEvent(event)
                    // 終端(done / error)を渡したら、ホストが接続を閉じるのを待たずに戻る
                    if (event is BridgeEvent.Done || event is BridgeEvent.Error) break
                }
            }
        }
    }

    /**
     * SSE のイベントを [BridgeEvent] にする。**捨てる**もの: 未知のイベント名、壊れた JSON、`seq` が整数でない・範囲外、
     * 別の `session_id` を名乗るもの、必須の項目が無い・型が違うもの。`seq` は `data` の値を正とする(VoiceSession が重複を無視する)。
     */
    private fun toEvent(id: String, ev: SseEvent): BridgeEvent? {
        if (ev.name !in EVENT_NAMES) return null
        val o = try {
            JSONObject(ev.data)
        } catch (_: Exception) {
            return null
        }
        val sid = o.opt("session_id")
        if (sid != null && sid != JSONObject.NULL && sid != id) return null
        val seq = o.intOrNull("seq")?.takeIf { it in 0..VoiceLimits.MAX_EVENT_SEQ } ?: return null
        return when (ev.name) {
            "state" -> when (o.opt("state") as? String) {
                "listening" -> BridgeEvent.State(seq, HostState.LISTENING)
                "speech" -> BridgeEvent.State(seq, HostState.SPEECH)
                "waiting" -> BridgeEvent.State(seq, HostState.WAITING)
                else -> null
            }
            "transcript" -> {
                val text = o.opt("text") as? String ?: return null
                BridgeEvent.Transcript(seq, text, o.opt("final") as? Boolean ?: false)
            }
            "reply" -> toReply(seq, o)
            "error" -> {
                val code = (o.opt("code") as? String)?.let { DaidaiText.clean(it, MAX_EVENT_CODE_CHARS, keepNewlines = false) }
                    ?.takeIf { it.isNotEmpty() } ?: return null
                val message = (o.opt("message") as? String)
                    ?.let { DaidaiText.clean(it, BridgeHttp.HOST_MESSAGE_MAX_CHARS, keepNewlines = false) }?.takeIf { it.isNotEmpty() }
                BridgeEvent.Error(seq, code, message)
            }
            "done" -> BridgeEvent.Done(seq)
            else -> null
        }
    }

    private fun toReply(seq: Int, o: JSONObject): BridgeEvent.Reply? {
        val index = o.intOrNull("index")?.takeIf { it in 0..MAX_REPLY_INDEX } ?: return null
        val text = o.opt("text") as? String ?: return null
        var audioError = when (val e = o.opt("audio_error")) {
            null, JSONObject.NULL -> null
            is String -> DaidaiText.clean(e, MAX_EVENT_CODE_CHARS, keepNewlines = false)
            else -> "invalid_audio_error"
        }
        // 音声の記述が壊れていても、テキストは捨てない(音声だけ失敗として扱い、VoiceSession が固定文言を併記する)
        val audio = when (val a = o.opt("audio")) {
            null, JSONObject.NULL -> null
            is JSONObject -> parseAudio(a).also { if (it == null && audioError == null) audioError = "invalid_audio" }
            else -> null.also { if (audioError == null) audioError = "invalid_audio" }
        }
        return BridgeEvent.Reply(
            seq = seq, index = index, text = text, audio = audio, audioError = audioError,
            final = o.opt("final") as? Boolean ?: true,
            avatarPose = (o.opt("avatar_pose") as? String)?.takeIf { VoiceLimits.POSE_REGEX.matches(it) },
        )
    }

    private fun parseAudio(a: JSONObject): ReplyAudioInfo? {
        val format = (a.opt("format") as? String)?.takeIf { it.length <= MAX_AUDIO_FORMAT_CHARS } ?: return null
        val rate = a.intOrNull("sample_rate") ?: return null
        val bytes = a.longOrNull("bytes") ?: return null
        val duration = a.longOrNull("duration_ms") ?: return null
        return ReplyAudioInfo(format, rate, bytes, duration)
    }

    // =========================================================================================
    // 返答音声(§5.2.4)

    override fun fetchReplyAudio(id: String, index: Int): ByteArray {
        requireSessionId(id)
        if (index !in 0..MAX_REPLY_INDEX) throw BridgeException(BridgeFailure.MALFORMED)
        return exchange(
            "GET", "/v1/voice/sessions/$id/replies/$index/audio", ACCEPT_WAV, abortable = true, totalMs = replyAudioTimeoutMs,
            readTimeoutMs = readMs(replyAudioTimeoutMs),
        ) { c ->
            val code = c.responseCode
            if (code != 200) failStatus(c, code, forChunk = false)
            if (WAV_TYPES.none { hasMediaType(c.contentType, it) }) fail(BridgeFailure.MALFORMED, code)
            // Content-Length は信用しない(chunked で来ることもある)が、10 MB を超える宣言は読まずに断る
            if (c.contentLengthLong > WavValidator.MAX_BYTES) fail(BridgeFailure.MALFORMED, code)
            try {
                WavValidator.read(c.inputStream)
            } catch (_: WavFormatException) {
                fail(BridgeFailure.MALFORMED, code)
            }
        }
    }

    // =========================================================================================
    // 取消(§5.2.5)

    /** 中断の対象にしない接続で送る(停止の順序は abort → cancel)。`204` と `404` を成功とする。冪等。 */
    override fun cancel(id: String) {
        requireSessionId(id)
        exchange(
            "DELETE", "/v1/voice/sessions/$id", ACCEPT_JSON, abortable = false, totalMs = cancelTimeoutMs, readTimeoutMs = readMs(cancelTimeoutMs),
        ) { c ->
            val code = c.responseCode
            if (code != 204 && code != 404) failStatus(c, code, forChunk = false)
        }
    }

    // =========================================================================================
    // 共通

    /** 内側(期限のウォッチドッグの中)で投げる失敗。[BridgeHttp.withDeadline] は [RuntimeException] を期限の判定に使うので、これで包む。 */
    private class Fail(val error: BridgeException) : RuntimeException(null, null, false, false)

    private fun fail(kind: BridgeFailure, status: Int = 0, err: BridgeHttp.HostError? = null): Nothing =
        throw Fail(BridgeException(kind, status, err?.code, err?.message))

    private fun requireSessionId(id: String) {
        if (!VoiceLimits.SESSION_ID_REGEX.matches(id)) throw BridgeException(BridgeFailure.MALFORMED)
    }

    private fun readMs(totalMs: Long): Int = totalMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * 1 回の通信。接続を開き、ヘッダと本文を付け、(中断の対象なら)登録し、`handle` で応答を処理する。`totalMs` があれば全体の期限を課す。
     * 失敗は [BridgeException] で投げる: [Fail] はそのまま、I/O の失敗・期限・中断は `NETWORK`。接続は必ず登録を外して切る。
     */
    private fun <T> exchange(
        method: String,
        path: String,
        accept: String,
        abortable: Boolean,
        totalMs: Long?,
        readTimeoutMs: Int,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
        handle: (HttpURLConnection) -> T,
    ): T {
        if (!configured) throw BridgeException(BridgeFailure.CLIENT_ERROR) // 通信しない(未設定・不正なトークン)
        val startEpoch = synchronized(lock) { epoch }
        var conn: HttpURLConnection? = null
        try {
            val c = try {
                BridgeHttp.open(base, path, bearer, connectTimeoutMs, readTimeoutMs, accept)
            } catch (_: IOException) {
                throw BridgeException(BridgeFailure.CLIENT_ERROR) // 接続前の設定の失敗(メッセージにトークンが入りうるので見ない)
            } catch (_: RuntimeException) {
                throw BridgeException(BridgeFailure.CLIENT_ERROR)
            }
            conn = c
            c.requestMethod = method
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
            }
            if (abortable) {
                val aborted = synchronized(lock) {
                    if (epoch != startEpoch) true else { active.add(c); false }
                }
                if (aborted) throw IOException("aborted") // 呼び出しが始まってから登録するまでの間に abort が来た
            }
            val io = {
                if (body != null) c.outputStream.use { it.write(body) }
                handle(c)
            }
            return if (totalMs != null) BridgeHttp.withDeadline(c, totalMs, io) else io()
        } catch (e: Fail) {
            throw e.error
        } catch (e: BridgeException) {
            throw e
        } catch (e: IOException) {
            throw BridgeException(BridgeFailure.NETWORK, cause = e)
        } catch (_: RuntimeException) {
            // 切断と競合した JDK 内部の例外など。メッセージ(URL・ヘッダを含みうる)は見ない
            throw BridgeException(BridgeFailure.NETWORK)
        } finally {
            conn?.let {
                synchronized(lock) { active.remove(it) }
                try {
                    it.disconnect()
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    /** 期待したステータス以外。本文(4 KB まで)から `error.code` / `error.message` を取り出して分類し、投げる。 */
    private fun failStatus(c: HttpURLConnection, code: Int, forChunk: Boolean): Nothing {
        val err = if (code in 400..599) BridgeHttp.readHostError(c) else BridgeHttp.NO_HOST_ERROR
        val kind = when {
            code in 300..399 -> BridgeFailure.CLIENT_ERROR // 追従しない。再送もしない
            forChunk && code == 409 -> when (err.code) {
                "ended" -> BridgeFailure.ENDED
                "out_of_order" -> BridgeFailure.OUT_OF_ORDER
                "seq_conflict" -> BridgeFailure.SEQ_CONFLICT
                else -> BridgeFailure.CLIENT_ERROR // 知らない 409 を「正常な終了」にしない
            }
            code == 410 -> BridgeFailure.EXPIRED
            code == 404 -> BridgeFailure.NOT_FOUND
            code == 503 && err.code == "asr_unavailable" -> BridgeFailure.ASR_UNAVAILABLE
            code in 500..599 -> BridgeFailure.SERVER_ERROR
            code in 400..499 -> BridgeFailure.CLIENT_ERROR
            else -> BridgeFailure.MALFORMED // 期待しない 1xx / 2xx
        }
        fail(kind, code, err)
    }

    /** 成功応答の JSON 本文(4 KB まで)。超過・JSON でない・オブジェクトでないときは `MALFORMED`。 */
    private fun readJson(c: HttpURLConnection, code: Int): JSONObject {
        if (c.contentLengthLong > BridgeHttp.MAX_BODY_BYTES) fail(BridgeFailure.MALFORMED, code)
        val bytes = BridgeHttp.readBounded(c.inputStream, BridgeHttp.MAX_BODY_BYTES) ?: fail(BridgeFailure.MALFORMED, code)
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            fail(BridgeFailure.MALFORMED, code)
        }
    }

    private fun hasMediaType(contentType: String?, expected: String): Boolean =
        contentType != null && contentType.substringBefore(';').trim().equals(expected, ignoreCase = true)

    /** JSON の整数(`Int`)だけ。文字列・小数・範囲外は null(org.json の `optInt` のような型の読み替えをしない)。 */
    private fun JSONObject.intOrNull(key: String): Int? = when (val v = opt(key)) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.longOrNull(key: String): Long? = when (val v = opt(key)) {
        is Int -> v.toLong()
        is Long -> v
        else -> null
    }

    companion object {
        /** 自分の期限は、呼び出し側のウォッチドッグ(作成 5 秒・チャンク 4 秒・返答音声 20 秒)よりこれだけ長い(呼び出し側が先に効く) */
        const val DEADLINE_MARGIN_MS = 1_000L

        /** 取消(DELETE)の全体の期限。ベストエフォートなので短い */
        const val CANCEL_TIMEOUT_MS = 3_000L

        /** SSE の読み取りタイムアウト。ホストの keepalive(15 秒ごと)より長い */
        const val EVENTS_READ_TIMEOUT_MS = 40_000

        /** `reply.index` / 返答音声のパスに入れる整数の上限(初版は 0 だけだが、拡張の余地を小さな範囲で持つ) */
        const val MAX_REPLY_INDEX = 99

        private const val ACCEPT_JSON = "application/json"
        private const val ACCEPT_SSE = "text/event-stream"
        private const val ACCEPT_WAV = "audio/wav"
        private val WAV_TYPES = listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        private val EVENT_NAMES = setOf("state", "transcript", "reply", "error", "done")
        private const val MAX_EVENT_CODE_CHARS = 64
        private const val MAX_AUDIO_FORMAT_CHARS = 16
    }
}
