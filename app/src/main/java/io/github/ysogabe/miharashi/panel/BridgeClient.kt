package io.github.ysogabe.miharashi.panel

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpRetryException
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
 *
 * 取りこぼさない仕組み(世代): クライアントは中断の世代(`epoch`)を持ち、abort はロックの中で世代を進めて、登録済みの接続をすべて切る。
 * 各呼び出しは始まるときに世代を取り、**接続の登録と、通信を始める直前・接続が済んだ直後(本文を送る前)の 3 回、ロックの中で世代の一致を確かめる**。
 * 一致しなければ(= その呼び出しが始まってから abort が来た)通信せず、接続を切って `NETWORK`(取消)で投げる。
 *
 * 作成([createSession])は、さらに呼び出し側の取消の照合 `isCurrent` を同じ 3 か所で見る(登録は世代の一致と**同じロックの中**)。false なら通信せず
 * [CreateCancelledException] で投げる。取消は「先に許可を進め、そのあとで abort」なので、abort が済んで世代が進んだあとに呼び出しが世代を取り直しても、
 * この照合が false を返して止める(世代だけだと、取り直した世代では中断が効かない)。照合を通ってから本文を書き始める(出力を取る)までの一瞬だけは残り、
 * それは送り始めたあとの取消として、呼び出し側の後始末(遅れて届く 201 の DELETE と会話の終わり)が回収する。`isCurrent` は、接続の登録のときはロックの中でも呼ぶので、ブロックしないこと。
 * 未接続の `HttpURLConnection.disconnect()` は、その後の接続を禁止しない(JDK は何もしない)ので、`disconnect` だけには頼らない:
 * 接続前の abort は直前の確認が、`connect()` の最中の abort は接続が済んだ直後の確認が拾い、いずれも本文・要求の送信より前に止める。
 * 接続が済んだあとの abort は、登録済みの接続への `disconnect` が効く。abort より**後**に始まった呼び出しは新しい世代なので有効。
 * [cancel] と [endConversation] の接続は中断の対象外(登録も確認もしない)で、abort の後でも送れる。
 *
 * ## 送信は 1 回の呼び出しにつき HTTP 要求 1 回
 * 本文のある POST(作成・チャンク)は、本文の長さを先に決めたストリーミング(`setFixedLengthStreamingMode`)で送る。JDK は
 * ストリーミングの要求を自動では再送しない(バッファ型の POST は通信失敗のときに最大で何度も再送し、同じチャンクが重複して届く)。
 * 再送は [VoiceSession] が 1 回だけ行う(§5.2.2)。ストリーミングでは `401` が `HttpRetryException` になるので、捕まえて
 * `CLIENT_ERROR`(status 401)にする。全要求に `Connection: close` を付け、使い回した古い接続での失敗を避ける。
 * (GET・DELETE は冪等なので、JDK が 1 回だけ黙って再送することがあっても害はない。)
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
    private val cueTimeoutMs: Long = VoiceLimits.CUE_FETCH_TIMEOUT_MS,
) : BridgeApi, CueFetcher {
    private val base = baseUrl.trim()
    private val bearer = token.trim()
    private val configured = base.isNotEmpty() && bearer.isNotEmpty() && Settings.validateDaidaiUrl(base) == null

    // ---- 中断(abort)の管理
    private val lock = Any()
    private var epoch = 0
    private val active: MutableSet<HttpURLConnection> = Collections.newSetFromMap(IdentityHashMap())

    /** 試験用の差し込み口(競合を再現するため、登録の直後・通信を始める直前で処理を止める)。本番では null。 */
    internal interface TestHooks {
        /** 接続の登録の直後(通信を始める前の確認より前) */
        fun afterRegister(path: String) {}

        /** 通信を始める直前の確認のあと、`connect()` の直前 */
        fun beforeConnect(path: String) {}

        /** `connect()` が済んだあと、本文を書く直前の確認より前 */
        fun beforeBody(path: String) {}
    }

    @Volatile internal var testHooks: TestHooks? = null

    override fun toString() = "BridgeClient(configured=$configured)"

    /** 通信中の接続をすべて切り、これまでに始まった呼び出しを取り消す。ノンブロッキング・冪等。これから始まる呼び出しは巻き込まない(世代で見分ける)。 */
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

    override fun createSession(req: SessionRequest, isCurrent: () -> Boolean): SessionInfo {
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
            .also { o ->
                // 省略できる。null なら書かない(旧 Panel とバイト単位で同じ)。JSON の null は送らない(新しいホストは 400 にする)
                req.conversation?.let { c -> o.put("conversation", JSONObject().also { j -> c.handle?.let { h -> j.put("handle", h) } }) }
            }
            .toString().toByteArray(Charsets.UTF_8)
        return exchange(
            "POST", "/v1/voice/sessions", ACCEPT_JSON, abortable = true, totalMs = createTimeoutMs, readTimeoutMs = readMs(createTimeoutMs),
            body = body, contentType = "application/json", isCurrent = isCurrent,
        ) { c ->
            val code = c.responseCode
            if (code != 201) failStatus(c, code, forChunk = false)
            val o = readJson(c, code)
            // 応答のほかの項目(URL・パスのように見えるもの)は読まない。使うのは session_id と limits だけ
            val id = (o.opt("session_id") as? String)?.takeIf { VoiceLimits.SESSION_ID_REGEX.matches(it) }
                ?: fail(BridgeFailure.MALFORMED, code)
            SessionInfo(id, parseLimits(o.opt("limits")), parseConversation(o.opt("conversation")))
        }
    }

    /**
     * 応答の `conversation`(辞書のときだけ。それ以外は null = 旧ホストと同じ「会話なし」)。`handle` は正規表現に**完全一致**したものだけ
     * (外れたら null。記号・長さ違い・末尾の改行・文字列でない型を採らない)。`continue` は boolean の false のときだけ false(省略・別の型は true)。
     * `context` は boolean のときだけ値、それ以外は null。ほかの項目は読まない。
     */
    private fun parseConversation(v: Any?): ConversationInfo? {
        val o = v as? JSONObject ?: return null
        val handle = (o.opt("handle") as? String)?.takeIf { VoiceLimits.CONVERSATION_HANDLE_REGEX.matches(it) }
        return ConversationInfo(handle, o.opt("context") as? Boolean, (o.opt("continue") as? Boolean) != false)
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
    // 会話の終わり(段階 2a-P。設計書(段階 2)§6.1)

    /**
     * `POST /v1/voice/conversations/end`、本文 `{"handle":"…"}`。**ハンドルを URL に入れない**(アクセスログに残らないように)。
     * 検証は通信の外(`exchange` は `RuntimeException` を通信失敗に畳むので、ここで先に投げる)。中断の対象にしない接続で、[cancel] と同じ期限。
     * `204` と、旧ホストの `404` を成功とする。
     */
    override fun endConversation(handle: String) {
        require(VoiceLimits.CONVERSATION_HANDLE_REGEX.matches(handle)) { "invalid conversation handle" } // メッセージにハンドルを入れない
        val body = JSONObject().put("handle", handle).toString().toByteArray(Charsets.UTF_8)
        exchange(
            "POST", "/v1/voice/conversations/end", ACCEPT_JSON, abortable = false, totalMs = cancelTimeoutMs, readTimeoutMs = readMs(cancelTimeoutMs),
            body = body, contentType = "application/json",
        ) { c ->
            val code = c.responseCode
            if (code != 204 && code != 404) failStatus(c, code, forChunk = false)
        }
    }

    // =========================================================================================
    // 挨拶の音声(段 G。設計書(段階 2)§5.8.3。ホストの契約は local-model-lab の docs/panel-greeting-cues.md)

    /**
     * `GET /v1/voice/cues`。本文は [VoiceLimits.CUE_MANIFEST_MAX_BYTES] まで。使うのは各項目の `sha256`(小文字の 16 進 64 文字)と `ms`
     * (1〜5000 の整数)だけで、名前が `^[a-z][a-z0-9_]{0,31}$` に合わない項目・形の違う項目は捨てる(`text` などほかの項目は読まない)。
     * 中断([abortInFlight])の対象にしない接続で、セッションの通信と独立に送る。404 は `NOT_FOUND`(挨拶が無い)。
     */
    override fun fetchCueManifest(): CueManifest =
        exchange(
            "GET", "/v1/voice/cues", ACCEPT_JSON, abortable = false, totalMs = cueTimeoutMs, readTimeoutMs = readMs(cueTimeoutMs),
        ) { c ->
            val code = c.responseCode
            if (code != 200) failStatus(c, code, forChunk = false)
            if (!hasMediaType(c.contentType, ACCEPT_JSON)) fail(BridgeFailure.MALFORMED, code)
            val o = readJson(c, code, VoiceLimits.CUE_MANIFEST_MAX_BYTES)
            val cues = o.opt("cues") as? JSONObject ?: fail(BridgeFailure.MALFORMED, code)
            val out = HashMap<String, CueEntry>()
            for (name in cues.keys()) {
                if (!CUE_NAME_REGEX.matches(name)) continue
                val e = cues.opt(name) as? JSONObject ?: continue
                val sha = (e.opt("sha256") as? String)?.takeIf { SHA256_REGEX.matches(it) } ?: continue
                val ms = e.longOrNull("ms")?.takeIf { it in 1..VoiceLimits.CUE_MAX_MS } ?: continue
                out[name] = CueEntry(sha, ms)
            }
            val voice = (o.opt("voice") as? String)?.takeIf { it.length <= MAX_CUE_VOICE_CHARS }
            CueManifest(voice, out)
        }

    /**
     * `GET /v1/voice/cues/{name}`。`name` は [StartCue.NAMES] のどれかだけ(パスに入るのは Panel が持つ固定の名前。目録の鍵は入れない)。
     * `audio/wav` で、[VoiceLimits.CUE_MAX_BYTES] まで、[WavValidator] を通ったものだけ返す。中断の対象にしない接続。
     */
    override fun fetchCue(name: String): ByteArray {
        if (name !in StartCue.NAMES) throw BridgeException(BridgeFailure.MALFORMED)
        return exchange(
            "GET", "/v1/voice/cues/$name", ACCEPT_WAV, abortable = false, totalMs = cueTimeoutMs, readTimeoutMs = readMs(cueTimeoutMs),
        ) { c ->
            val code = c.responseCode
            if (code != 200) failStatus(c, code, forChunk = false)
            if (WAV_TYPES.none { hasMediaType(c.contentType, it) }) fail(BridgeFailure.MALFORMED, code)
            if (c.contentLengthLong > VoiceLimits.CUE_MAX_BYTES) fail(BridgeFailure.MALFORMED, code)
            val bytes = BridgeHttp.readBounded(c.inputStream, VoiceLimits.CUE_MAX_BYTES) ?: fail(BridgeFailure.MALFORMED, code)
            try {
                WavValidator.validate(bytes)
            } catch (_: WavFormatException) {
                fail(BridgeFailure.MALFORMED, code)
            }
            bytes
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
     * 1 回の通信(HTTP 要求 1 回)。接続を開き、ヘッダと本文を付け、(中断の対象なら)登録し、`handle` で応答を処理する。`totalMs` があれば全体の期限を課す。
     * 失敗は [BridgeException] で投げる: [Fail] はそのまま、I/O の失敗・期限・中断は `NETWORK`。接続は必ず登録を外して切る。
     * 中断の対象の呼び出しは、始まってから abort が来ていたら通信しない(クラスの説明の「世代」)。
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
        isCurrent: (() -> Boolean)? = null,
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
            c.setRequestProperty("Connection", "close") // 古い接続の使い回しによる失敗を避ける(JDK は "close" だけ許す)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size) // 自動の再送をさせない(再送は VoiceSession が 1 回だけ)
            }
            if (abortable) {
                // 世代の一致と取消の照合を**同じロックの中**で見る。取消は先に許可を進めてから abortInFlight(世代を進める)するので、
                // 世代を取り直したあとで来た取消も、ここ(か以後の照合)で必ず拾える
                var cancelled = false
                val registered = synchronized(lock) {
                    if (epoch != startEpoch) false
                    else if (isCurrent != null && !isCurrent()) { cancelled = true; false }
                    else { active.add(c); true }
                }
                if (cancelled) throw CreateCancelledException()
                if (!registered) throw IOException("aborted") // 呼び出しが始まってから登録するまでの間に abort が来た
                testHooks?.afterRegister(path)
                ensureNotAborted(c, startEpoch, isCurrent) // 通信を始める直前の確認
            }
            val io = {
                if (abortable) testHooks?.beforeConnect(path)
                c.connect() // 要求はまだ送らない(JDK は getOutputStream / getInputStream で送る)
                if (abortable) {
                    testHooks?.beforeBody(path)
                    // connect の最中に来た abort(disconnect は効かない)と取消を、本文を書く前に拾う(固定長のストリーミングは、出力を取る時点で要求を送る)
                    ensureNotAborted(c, startEpoch, isCurrent)
                }
                if (body != null) c.outputStream.use { it.write(body) }
                handle(c)
            }
            return if (totalMs != null) BridgeHttp.withDeadline(c, totalMs, io) else io()
        } catch (e: Fail) {
            throw e.error
        } catch (e: CreateCancelledException) {
            throw e // 取消(通信の失敗ではない)。要求は送っていない
        } catch (e: BridgeException) {
            throw e
        } catch (e: HttpRetryException) {
            // ストリーミングの要求で 401(認証のやり直し)を受けると JDK はこれを投げる。本文は読まない。401 などの 4xx は再送しない失敗
            throw BridgeException(BridgeFailure.CLIENT_ERROR, status = e.responseCode())
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

    /**
     * 世代が変わっていれば(この呼び出しが始まってから abort が来た)、接続を切って中断として投げる。
     * `isCurrent` があり false なら(作成の取消)、接続を切って [CreateCancelledException] で投げる。どちらも要求を送る前。
     */
    private fun ensureNotAborted(c: HttpURLConnection, startEpoch: Int, isCurrent: (() -> Boolean)? = null) {
        val aborted = synchronized(lock) { epoch != startEpoch }
        val cancelled = !aborted && isCurrent != null && !isCurrent()
        if (!aborted && !cancelled) return
        try {
            c.disconnect()
        } catch (_: RuntimeException) {
        }
        if (cancelled) throw CreateCancelledException()
        throw IOException("aborted")
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
    private fun readJson(c: HttpURLConnection, code: Int, limit: Int = BridgeHttp.MAX_BODY_BYTES): JSONObject {
        if (c.contentLengthLong > limit) fail(BridgeFailure.MALFORMED, code)
        val bytes = BridgeHttp.readBounded(c.inputStream, limit) ?: fail(BridgeFailure.MALFORMED, code)
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
        private const val MAX_CUE_VOICE_CHARS = 64
        private val CUE_NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
