package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** `BridgeClient`(HTTP + SSE)の単体試験(設計書 §5.2.0〜§5.2.5, §7)。MockWebServer と素の ServerSocket で契約どおりの相手を再現する。 */
class BridgeClientTest {
    private lateinit var server: MockWebServer
    private val token = "bridge-token-0123456789"
    private val panelToken = "panel-token-ABCDEFGHIJ"
    private val sid = "s-7f3a"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun client(
        base: String = base(), t: String = token, createMs: Long = 5_000, chunkMs: Long = 4_000, audioMs: Long = 20_000,
        cancelMs: Long = 3_000, eventsReadMs: Int = 40_000,
    ) = BridgeClient(base, t, 3_000, createMs, chunkMs, audioMs, cancelMs, eventsReadMs)

    private fun req() = server.takeRequest(3, TimeUnit.SECONDS)!!

    private fun watchdogThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == BridgeHttp.WATCHDOG_THREAD_NAME }

    private inline fun <T> failure(block: () -> T): BridgeException {
        try {
            block()
        } catch (e: BridgeException) {
            return e
        }
        fail("BridgeException が投げられるはず")
        throw AssertionError()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun err(code: Int, errCode: String?, message: String = "m") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
            .setBody(if (errCode == null) "" else """{"error":{"code":"$errCode","message":"$message"}}""")

    private val request = SessionRequest("echo-show-5", "0.3.0", StartReason.SWIPE, null)

    // =========================================================================================
    // 作成(§5.2.1)

    @Test fun createPostsTheSpecBodyToTheFixedPathWithOnlyTheBridgeBearer() {
        server.enqueue(
            json("""{"session_id":"$sid","limits":{"no_speech_s":10,"max_utterance_s":30,"reply_timeout_s":45,"idle_expire_s":15,"retain_s":60}}""", 201),
        )
        val info = client().createSession(SessionRequest("echo-show-5", "0.3.0", StartReason.NOTIFICATION, "n-1"))
        assertEquals(sid, info.sessionId)
        assertEquals(HostLimits(10, 30, 45, 15, 60), info.limits)
        val r = req()
        assertEquals("POST", r.method)
        assertEquals("/v1/voice/sessions", r.path)
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        assertFalse("panel のトークンはどこにも載らない", r.headers.toString().contains(panelToken))
        val o = JSONObject(r.body.readUtf8())
        assertEquals("echo-show-5", o.getJSONObject("client").getString("id"))
        assertEquals("0.3.0", o.getJSONObject("client").getString("app_version"))
        assertEquals("notification", o.getString("reason"))
        assertEquals("n-1", o.getString("notice_id"))
        val audio = o.getJSONObject("audio")
        assertEquals("pcm_s16le", audio.getString("encoding"))
        assertEquals(16_000, audio.getInt("sample_rate"))
        assertEquals(1, audio.getInt("channels"))
        assertEquals(10, o.getJSONObject("limits").getInt("no_speech_s"))
        assertEquals(30, o.getJSONObject("limits").getInt("max_utterance_s"))
    }

    @Test fun createSendsNullNoticeIdAndSwipeReason() {
        server.enqueue(json("""{"session_id":"$sid"}""", 201))
        client().createSession(request)
        val o = JSONObject(req().body.readUtf8())
        assertTrue(o.has("notice_id"))
        assertTrue(o.isNull("notice_id"))
        assertEquals("swipe", o.getString("reason"))
    }

    @Test fun limitsThatAreNotIntegersAreReportedAsMissing() {
        server.enqueue(
            json(
                """{"session_id":"$sid","limits":{"no_speech_s":"10","max_utterance_s":30.5,"reply_timeout_s":45,"idle_expire_s":null,"retain_s":99999999999}}""",
                201,
            ),
        )
        val l = client().createSession(request).limits
        assertNull("文字列", l.noSpeechS)
        assertNull("小数", l.maxUtteranceS)
        assertEquals(45L, l.replyTimeoutS)
        assertNull("null", l.idleExpireS)
        assertEquals("巨大な整数はそのまま Long で(丸めは VoiceSession)", 99_999_999_999L, l.retainS)
    }

    @Test fun missingOrWrongTypedLimitsAreAllMissing() {
        server.enqueue(json("""{"session_id":"$sid"}""", 201))
        assertEquals(HostLimits(), client().createSession(request).limits)
        server.enqueue(json("""{"session_id":"$sid","limits":"many"}""", 201))
        assertEquals(HostLimits(), client().createSession(request).limits)
    }

    @Test fun badSessionIdsFailTheCreation() {
        val bad = listOf("", "a/b", "../x", "a b", "x".repeat(65), "id\\n", "s-1?x=1", "http://evil/x", "日本語", "a.b")
        for (b in bad) {
            server.enqueue(json(JSONObject().put("session_id", b).toString(), 201))
            assertEquals("session_id=<$b>", BridgeFailure.MALFORMED, failure { client().createSession(request) }.kind)
        }
        for (notString in listOf("""{"session_id":123}""", """{"session_id":null}""", """{}""", """{"session_id":["a"]}""")) {
            server.enqueue(json(notString, 201))
            assertEquals(notString, BridgeFailure.MALFORMED, failure { client().createSession(request) }.kind)
        }
        server.enqueue(json(JSONObject().put("session_id", "A".repeat(64)).toString(), 201))
        assertEquals("64 文字までは通る", 64, client().createSession(request).sessionId.length)
    }

    @Test fun createOtherStatusesAndBodiesAreClassified() {
        server.enqueue(json("""{"session_id":"$sid"}""", 200))
        assertEquals("201 以外の成功", BridgeFailure.MALFORMED, failure { client().createSession(request) }.kind)
        server.enqueue(json("not json", 201))
        assertEquals(BridgeFailure.MALFORMED, failure { client().createSession(request) }.kind)
        server.enqueue(json("[1,2]", 201))
        assertEquals(BridgeFailure.MALFORMED, failure { client().createSession(request) }.kind)
        server.enqueue(err(503, "asr_unavailable", "asr down"))
        val e = failure { client().createSession(request) }
        assertEquals(BridgeFailure.ASR_UNAVAILABLE, e.kind)
        assertEquals(503, e.status)
        assertEquals("asr_unavailable", e.hostCode)
        server.enqueue(err(503, "busy"))
        assertEquals(BridgeFailure.SERVER_ERROR, failure { client().createSession(request) }.kind)
        for (code in listOf(400, 401, 403, 409, 429)) {
            server.enqueue(err(code, "x"))
            val f = failure { client().createSession(request) }
            assertEquals("HTTP $code", BridgeFailure.CLIENT_ERROR, f.kind)
            assertEquals(code, f.status)
            assertFalse(f.isRetryable)
        }
    }

    @Test fun createIgnoresUrlsAndPathsInTheResponse_S10() {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody("""{"state":"ended","accepted_seq":0}"""))
            server.enqueue(
                json(
                    """{"session_id":"$sid","audio_url":"${other.url("/steal")}","events_url":"${other.url("/ev")}","path":"/x/y","url":"${other.url("/")}"}""",
                    201,
                ),
            )
            server.enqueue(json("""{"state":"listening","accepted_seq":0}"""))
            val c = client()
            val info = c.createSession(request)
            c.sendAudio(info.sessionId, 0, ByteArray(640), false)
            req()
            assertEquals("/v1/voice/sessions/$sid/audio", req().path)
            assertEquals("応答に書かれた別ホストには接続しない", 0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    // =========================================================================================
    // チャンク(§5.2.2)

    @Test fun sendAudioPostsRawPcmWithSeqHeader() {
        server.enqueue(json("""{"state":"listening","accepted_seq":5}"""))
        val pcm = ByteArray(1280) { (it % 251).toByte() }
        val ack = client().sendAudio(sid, 5, pcm, false)
        assertEquals(ChunkAck(AckState.LISTENING, 5), ack)
        val r = req()
        assertEquals("POST", r.method)
        assertEquals("/v1/voice/sessions/$sid/audio", r.path)
        assertEquals("application/octet-stream", r.getHeader("Content-Type"))
        assertEquals("5", r.getHeader("X-Chunk-Seq"))
        assertNull("final でなければ X-Chunk-Final は付けない", r.getHeader("X-Chunk-Final"))
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertEquals(pcm.size.toLong(), r.getHeader("Content-Length")!!.toLong())
        assertArrayEquals(pcm, r.body.readByteArray())
    }

    @Test fun sendAudioMarksOnlyTheLastChunkFinal() {
        server.enqueue(json("""{"state":"ended","accepted_seq":9}"""))
        val ack = client().sendAudio(sid, 9, ByteArray(640), true)
        assertEquals(ChunkAck(AckState.ENDED, 9), ack)
        assertEquals("1", req().getHeader("X-Chunk-Final"))
    }

    @Test fun ackStatesAreParsed() {
        for ((wire, state) in listOf("listening" to AckState.LISTENING, "speech" to AckState.SPEECH, "ended" to AckState.ENDED)) {
            server.enqueue(json("""{"state":"$wire","accepted_seq":3}"""))
            assertEquals(ChunkAck(state, 3), client().sendAudio(sid, 3, ByteArray(640), false))
        }
    }

    @Test fun malformedAcksAreRejected() {
        val bodies = listOf(
            """{"state":"bogus","accepted_seq":1}""", """{"accepted_seq":1}""", """{"state":"speech"}""",
            """{"state":"speech","accepted_seq":"1"}""", """{"state":"speech","accepted_seq":1.5}""", """{"state":"speech","accepted_seq":-1}""",
            """{"state":"speech","accepted_seq":1000000000}""", """{"state":5,"accepted_seq":1}""", "garbage", "", "[]",
        )
        for (b in bodies) {
            server.enqueue(json(b))
            assertEquals("body=<$b>", BridgeFailure.MALFORMED, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
        }
        // 4 KB 超の成功応答
        server.enqueue(json("""{"state":"speech","accepted_seq":1,"pad":"${"x".repeat(4100)}"}"""))
        assertEquals(BridgeFailure.MALFORMED, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
        // 204 など、期待しない成功
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(BridgeFailure.MALFORMED, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
    }

    @Test fun conflictIsSplitByTheBodyCode() {
        val cases = listOf("ended" to BridgeFailure.ENDED, "out_of_order" to BridgeFailure.OUT_OF_ORDER, "seq_conflict" to BridgeFailure.SEQ_CONFLICT)
        for ((code, kind) in cases) {
            server.enqueue(err(409, code, "detail"))
            val e = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
            assertEquals(kind, e.kind)
            assertEquals(409, e.status)
            assertEquals(code, e.hostCode)
            assertEquals("detail", e.hostMessage)
            assertFalse("409 は再送しない", e.isRetryable)
        }
        // 知らない・無い・壊れた 409 を「正常な終了」にしない
        for (resp in listOf(err(409, "weird"), err(409, null), MockResponse().setResponseCode(409).setBody("<html>conflict</html>"))) {
            server.enqueue(resp)
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
        }
    }

    @Test fun statusCodesAreClassified() {
        fun kind(resp: MockResponse): BridgeException {
            server.enqueue(resp)
            return failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        }
        assertEquals(BridgeFailure.EXPIRED, kind(err(410, "expired")).kind)
        assertEquals(BridgeFailure.NOT_FOUND, kind(err(404, "not_found")).kind)
        assertEquals(BridgeFailure.NOT_FOUND, kind(MockResponse().setResponseCode(404)).kind)
        val asr = kind(err(503, "asr_unavailable"))
        assertEquals(BridgeFailure.ASR_UNAVAILABLE, asr.kind)
        assertEquals(503, asr.status)
        for (code in listOf(502, 503, 504)) {
            val e = kind(err(code, "oops"))
            assertEquals("HTTP $code", BridgeFailure.SERVER_ERROR, e.kind)
            assertTrue("再送の対象", e.isRetryable)
        }
        val e500 = kind(err(500, "oops"))
        assertEquals(BridgeFailure.SERVER_ERROR, e500.kind)
        assertFalse("500 は再送の対象にしない", e500.isRetryable)
        for (code in listOf(400, 401, 403, 413, 429)) {
            val e = kind(err(code, "denied"))
            assertEquals("HTTP $code", BridgeFailure.CLIENT_ERROR, e.kind)
            assertEquals(code, e.status)
            assertFalse(e.isRetryable)
        }
    }

    @Test fun redirectsAreNotFollowedAndAreNotRetried() {
        val other = MockWebServer().apply { start() }
        try {
            for (code in listOf(301, 302, 307, 308)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", other.url("/v1/voice/sessions/$sid/audio").toString()))
                val e = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
                assertEquals("HTTP $code", BridgeFailure.CLIENT_ERROR, e.kind)
                assertEquals(code, e.status)
                assertFalse(e.isRetryable)
            }
            // 同じホスト内のリダイレクトも追従しない
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
            assertEquals(5, server.requestCount)
            assertEquals("リダイレクト先には接続しない(トークンも送らない)", 0, other.requestCount)
            // 作成・取消・返答音声・SSE も同じ
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/").toString()))
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().createSession(request) }.kind)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/").toString()))
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().cancel(sid) }.kind)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/").toString()))
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().fetchReplyAudio(sid, 0) }.kind)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/").toString()))
            assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().events(sid, null) { } }.kind)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test fun errorBodyOverFourKilobytesIsNotUsedButTheStatusStillClassifies() {
        val pad = "x".repeat(5000)
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"error":{"code":"ended","message":"$pad"}}"""),
        )
        val e = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        assertEquals("4 KB 超の本文は使わない(409 の分類は本文の code に依るので CLIENT_ERROR)", BridgeFailure.CLIENT_ERROR, e.kind)
        assertNull(e.hostCode)
        assertNull(e.hostMessage)
        // 巨大な chunked の本文も、4 KB を超えた時点で打ち切る
        server.enqueue(MockResponse().setResponseCode(503).setChunkedBody("""{"error":{"code":"x","message":"${"y".repeat(300_000)}"}}""", 1024))
        val e2 = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        assertEquals(BridgeFailure.SERVER_ERROR, e2.kind)
        assertTrue(e2.isRetryable)
        assertNull(e2.hostMessage)
        // ちょうど 4 KB は使える
        val head = """{"error":{"code":"ended","message":""""
        val tail = """"}}"""
        val body = head + "z".repeat(4096 - head.length - tail.length) + tail
        assertEquals(4096, body.toByteArray().size)
        server.enqueue(MockResponse().setResponseCode(409).setBody(body))
        val e3 = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        assertEquals(BridgeFailure.ENDED, e3.kind)
        assertEquals("message は 200 文字に切る", BridgeHttp.HOST_MESSAGE_MAX_CHARS, e3.hostMessage!!.length)
    }

    @Test fun hostMessageIsCleanedAndNeverInTheExceptionMessageOrToString() {
        val nasty = "line1\nline2\u0000‮evil secret-transcript-text"
        server.enqueue(MockResponse().setResponseCode(400).setBody(JSONObject().put("error", JSONObject().put("code", "bad").put("message", nasty)).toString()))
        val e = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        val m = e.hostMessage!!
        assertFalse(m.contains('\n'))
        assertFalse(m.contains('\u0000'))
        assertFalse(m.contains('‮'))
        assertFalse("例外メッセージにホストの文字列を入れない", e.message!!.contains("secret"))
        assertFalse(e.toString().contains("secret"))
        // 不正な code は採らない
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"has space/and\nbad","message":"x"}}"""))
        assertNull(failure { client().sendAudio(sid, 1, ByteArray(640), false) }.hostCode)
    }

    @Test fun timeoutAndDisconnectAreNetworkFailures() {
        // 応答が来ない: 自分の全体の期限(ここでは 400 ms)でウォッチドッグが切る
        server.enqueue(json("""{"state":"listening","accepted_seq":1}""").setHeadersDelay(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val e = failure { client(chunkMs = 400).sendAudio(sid, 1, ByteArray(640), false) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(BridgeFailure.NETWORK, e.kind)
        assertTrue(e.isRetryable)
        assertTrue("期限で戻る($ms ms)", ms < 3_000)
        // 本文を 1 バイトずつ細く流す: 読み取りは進んでも、全体の期限で切る
        server.enqueue(json("""{"state":"listening","accepted_seq":1}""").throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val t1 = System.nanoTime()
        assertEquals(BridgeFailure.NETWORK, failure { client(chunkMs = 500).sendAudio(sid, 1, ByteArray(640), false) }.kind)
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 3_000)
        // 接続を切られる(JDK が POST を 1 回自分で再試行することがあるので 2 つ用意する)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(BridgeFailure.NETWORK, failure { client().sendAudio(sid, 1, ByteArray(640), false) }.kind)
        assertEquals("ウォッチドッグのスレッドは残らない", 0, watchdogThreads())
    }

    @Test fun connectionRefusedIsANetworkFailure() {
        val dead = ServerSocket(0).use { it.localPort }
        val e = failure { client(base = "http://127.0.0.1:$dead").sendAudio(sid, 1, ByteArray(640), false) }
        assertEquals(BridgeFailure.NETWORK, e.kind)
        assertEquals(0, e.status)
    }

    // =========================================================================================
    // 設定の不備・不正な id

    @Test fun invalidConfigurationNeverTouchesTheNetwork() {
        for (t in listOf("", "   ", "bad token with spaces", "tok\nen-0123456789", "トークン-0123456789")) {
            val e = failure { client(t = t).sendAudio(sid, 1, ByteArray(640), false) }
            assertEquals("token=<${t.length} chars>", BridgeFailure.CLIENT_ERROR, e.kind)
            assertFalse("トークンを例外に出さない", (e.message ?: "").contains("bad token") || (e.message ?: "").contains("トークン"))
        }
        for (b in listOf("", "ftp://127.0.0.1:1", "http://user:pw@127.0.0.1:1", "http://127.0.0.1:1/?q=1", "http://127.0.0.1:1/#f", "127.0.0.1:1")) {
            assertEquals("base=<$b>", BridgeFailure.CLIENT_ERROR, failure { client(base = b).createSession(request) }.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidSessionIdsAndIndexesNeverTouchTheNetwork() {
        val c = client()
        for (bad in listOf("", "a/b", "../x", "x".repeat(65), "a b", "a%2Fb", "s-1\r\nX: y")) {
            assertEquals(BridgeFailure.MALFORMED, failure { c.sendAudio(bad, 1, ByteArray(640), false) }.kind)
            assertEquals(BridgeFailure.MALFORMED, failure { c.events(bad, null) { } }.kind)
            assertEquals(BridgeFailure.MALFORMED, failure { c.fetchReplyAudio(bad, 0) }.kind)
            assertEquals(BridgeFailure.MALFORMED, failure { c.cancel(bad) }.kind)
        }
        for (bad in listOf(-1, 100, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(BridgeFailure.MALFORMED, failure { c.fetchReplyAudio(sid, bad) }.kind)
        }
        assertEquals(BridgeFailure.MALFORMED, failure { c.sendAudio(sid, -1, ByteArray(640), false) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun pathPrefixInTheUrlIsKept() {
        server.enqueue(json("""{"session_id":"$sid"}""", 201))
        client(base = base() + "/bridge/").createSession(request)
        assertEquals("/bridge/v1/voice/sessions", req().path)
    }

    @Test fun everyRequestCarriesOnlyTheBridgeBearer() {
        server.enqueue(json("""{"session_id":"$sid"}""", 201))
        server.enqueue(json("""{"state":"listening","accepted_seq":0}"""))
        server.enqueue(sse(ev("done", """{"seq":0}""", 0)))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(WavFixtures.valid())))
        server.enqueue(MockResponse().setResponseCode(204))
        val c = client()
        val id = c.createSession(request).sessionId
        c.sendAudio(id, 0, ByteArray(640), false)
        c.events(id, null) { }
        c.fetchReplyAudio(id, 0)
        c.cancel(id)
        repeat(5) {
            val r = req()
            assertEquals("Bearer $token", r.getHeader("Authorization"))
            assertFalse(r.headers.toString().contains(panelToken))
        }
    }

    // =========================================================================================
    // イベント(§5.2.3)

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun ev(name: String, data: String, id: Int? = null) = (id?.let { "id: $it\n" } ?: "") + "event: $name\ndata: $data\n\n"

    private fun collect(c: BridgeClient = client(), lastId: Int? = null): List<BridgeEvent> {
        val got = mutableListOf<BridgeEvent>()
        c.events(sid, lastId) { got += it }
        return got
    }

    @Test fun eventsRequestShapeAndLastEventIdValidation() {
        server.enqueue(sse(""))
        collect()
        val r = req()
        assertEquals("GET", r.method)
        assertEquals("/v1/voice/sessions/$sid/events", r.path)
        assertEquals("text/event-stream", r.getHeader("Accept"))
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertNull("初回は Last-Event-ID を送らない", r.getHeader("Last-Event-ID"))
        for (v in listOf(0, 12, 999_999_999)) {
            server.enqueue(sse(""))
            collect(lastId = v)
            assertEquals(v.toString(), req().getHeader("Last-Event-ID"))
        }
        for (v in listOf(-1, 1_000_000_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            server.enqueue(sse(""))
            collect(lastId = v)
            assertNull("範囲外($v)は送らない", req().getHeader("Last-Event-ID"))
        }
    }

    @Test fun eventsAreParsedAndBadOnesAreDroppedBeforeDelivery() {
        val audio = """{"format":"wav","sample_rate":24000,"bytes":123456,"duration_ms":4200}"""
        val body = buildString {
            append(": keepalive\n\n")
            append(ev("state", """{"seq":0,"state":"speech"}""", 0))
            append(ev("weird", """{"seq":1}""", 1)) // 未知のイベント名
            append("id: 2\ndata: {\"seq\":2,\"state\":\"speech\"}\n\n") // 名前なし(message)
            append(ev("state", "{not json", 3)) // 壊れた JSON
            append(ev("state", """{"seq":"4","state":"speech"}""", 4)) // seq が文字列
            append(ev("state", """{"seq":5.5,"state":"speech"}""", 5)) // seq が小数
            append(ev("state", """{"seq":-1,"state":"speech"}""")) // 負
            append(ev("state", """{"seq":1000000000,"state":"speech"}""")) // 範囲外
            append(ev("state", """{"state":"speech"}""")) // seq なし
            append(ev("transcript", """{"seq":6,"session_id":"other-session","text":"他人の","final":true}""", 6)) // 別セッション
            append(ev("state", """{"seq":7,"state":"bogus"}""", 7)) // 未知の state
            append(ev("state", "[1,2,3]", 8)) // オブジェクトでない
            append(ev("transcript", """{"seq":9,"final":true}""", 9)) // text なし
            append(": another comment\n\n")
            append(ev("transcript", """{"seq":10,"session_id":"$sid","text":"こんにちは","final":false}""", 10))
            append(ev("state", """{"seq":11,"state":"waiting"}""", 11))
            append(ev("reply", """{"seq":12,"index":0,"text":"返答","audio":$audio,"audio_error":null,"final":true,"avatar_pose":"talking"}""", 12))
            append(ev("done", """{"seq":13}""", 13))
            append(ev("state", """{"seq":14,"state":"speech"}""", 14)) // 終端のあと
        }
        server.enqueue(sse(body))
        val got = collect()
        assertEquals(
            listOf(
                BridgeEvent.State(0, HostState.SPEECH),
                BridgeEvent.Transcript(10, "こんにちは", false),
                BridgeEvent.State(11, HostState.WAITING),
                BridgeEvent.Reply(12, 0, "返答", ReplyAudioInfo("wav", 24_000, 123_456, 4_200), null, true, "talking"),
                BridgeEvent.Done(13),
            ),
            got,
        )
        assertEquals("終端 done のあとは渡さない", 5, got.size)
    }

    @Test fun errorEventIsTerminalAndCarriesCodeAndMessage() {
        server.enqueue(
            sse(
                ev("state", """{"seq":0,"state":"speech"}""", 0) + ev("error", """{"seq":1,"code":"no_speech","message":"nobody\nspoke"}""", 1) +
                    ev("done", """{"seq":2}""", 2),
            ),
        )
        val got = collect()
        assertEquals(2, got.size)
        val e = got[1] as BridgeEvent.Error
        assertEquals("no_speech", e.code)
        assertTrue(e.message!!.contains("nobody"))
        assertFalse(e.message!!.contains('\n'))
        // code の無い error は捨てる
        server.enqueue(sse(ev("error", """{"seq":0,"message":"x"}""", 0) + ev("done", """{"seq":1}""", 1)))
        assertEquals(listOf<BridgeEvent>(BridgeEvent.Done(1)), collect())
    }

    @Test fun replyWithBrokenAudioKeepsTheTextAndFlagsTheAudioAsFailed() {
        val bodies = listOf(
            """{"seq":0,"index":0,"text":"t","audio":null,"audio_error":"tts_failed","final":true}""" to ("tts_failed" to true),
            """{"seq":0,"index":0,"text":"t","final":true}""" to (null to true),
            """{"seq":0,"index":0,"text":"t","audio":"http://evil/x.wav","final":true}""" to ("invalid_audio" to true),
            """{"seq":0,"index":0,"text":"t","audio":{"format":"wav","sample_rate":"24000","bytes":1,"duration_ms":1},"final":true}""" to ("invalid_audio" to true),
            """{"seq":0,"index":0,"text":"t","audio":{"format":"wav","sample_rate":24000,"bytes":1.5,"duration_ms":1},"final":true}""" to ("invalid_audio" to true),
            """{"seq":0,"index":0,"text":"t","audio":{"format":"wav","sample_rate":24000},"final":true}""" to ("invalid_audio" to true),
        )
        for ((json, expect) in bodies) {
            server.enqueue(sse(ev("reply", json, 0)))
            val r = collect().single() as BridgeEvent.Reply
            assertEquals(json, expect.first, r.audioError)
            assertNull(json, r.audio)
            assertEquals("t", r.text)
        }
        // audio_error が文字列でない
        server.enqueue(sse(ev("reply", """{"seq":0,"index":0,"text":"t","audio":null,"audio_error":{"x":1}}""", 0)))
        assertNotNull((collect().single() as BridgeEvent.Reply).audioError)
    }

    @Test fun replyWithBadIndexTextOrPoseIsDroppedOrSanitized() {
        server.enqueue(
            sse(
                ev("reply", """{"seq":0,"index":-1,"text":"t"}""", 0) + ev("reply", """{"seq":1,"index":"0","text":"t"}""", 1) +
                    ev("reply", """{"seq":2,"index":0}""", 2) + ev("reply", """{"seq":3,"index":0,"text":5}""", 3) +
                    ev("reply", """{"seq":4,"index":0,"text":"ok","avatar_pose":"../../etc/passwd"}""", 4) + ev("done", """{"seq":5}""", 5),
            ),
        )
        val got = collect()
        assertEquals(2, got.size)
        val r = got[0] as BridgeEvent.Reply
        assertEquals(4, r.seq)
        assertNull("不正な avatar_pose は渡さない", r.avatarPose)
    }

    @Test fun eventsErrorStatusesAreClassified() {
        fun kind(resp: MockResponse): BridgeException {
            server.enqueue(resp)
            return failure { collect() }
        }
        assertEquals(BridgeFailure.NOT_FOUND, kind(err(404, "not_found")).kind)
        assertEquals(BridgeFailure.EXPIRED, kind(err(410, "expired")).kind)
        assertEquals(BridgeFailure.CLIENT_ERROR, kind(err(401, "unauthorized")).kind)
        assertEquals(BridgeFailure.CLIENT_ERROR, kind(err(403, "forbidden")).kind)
        assertEquals(BridgeFailure.SERVER_ERROR, kind(err(502, "bad_gateway")).kind)
        assertEquals(BridgeFailure.SERVER_ERROR, kind(err(503, "unavailable")).kind)
        assertEquals("イベントストリームでない 200", BridgeFailure.MALFORMED, kind(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>portal</html>")).kind)
        assertEquals(BridgeFailure.MALFORMED, kind(MockResponse().setBody("")).kind)
    }

    @Test fun contentTypeWithParametersIsAccepted() {
        server.enqueue(MockResponse().setHeader("Content-Type", "Text/Event-Stream; charset=utf-8").setBody(ev("done", """{"seq":0}""", 0)))
        assertEquals(listOf<BridgeEvent>(BridgeEvent.Done(0)), collect())
    }

    @Test fun eofWithoutTerminalReturnsNormally() {
        server.enqueue(sse(ev("state", """{"seq":0,"state":"speech"}""", 0) + ev("transcript", """{"seq":1,"text":"a","final":false}""", 1)))
        val got = collect()
        assertEquals(2, got.size) // 例外を投げずに戻る。VoiceSession は終端なしの成功を「途切れ」として再接続する
    }

    @Test fun oversizedSseLineIsAnErrorAfterDeliveringTheEarlierEvents() {
        val big = "x".repeat(SseParser.MAX_LINE_BYTES + 10)
        server.enqueue(sse(ev("state", """{"seq":0,"state":"speech"}""", 0) + "event: state\ndata: $big\n\n"))
        val got = mutableListOf<BridgeEvent>()
        val e = failure { client().events(sid, null) { got += it } }
        assertEquals(BridgeFailure.NETWORK, e.kind)
        assertTrue(e.cause is SseLimitException)
        assertFalse("例外に受信した内容を入れない", e.message!!.contains("xxxx") || (e.cause?.message ?: "").contains("xxxx"))
        assertEquals(listOf<BridgeEvent>(BridgeEvent.State(0, HostState.SPEECH)), got)
    }

    @Test fun oversizedSseEventIsAnError() {
        // 1 行は 8 KB 以内でも、1 イベントが 16 KB を超える(data を何行も並べる)
        val line = "data: " + "y".repeat(4_000) + "\n"
        server.enqueue(sse("event: state\n" + line.repeat(6) + "\n"))
        val e = failure { collect() }
        assertEquals(BridgeFailure.NETWORK, e.kind)
        assertTrue(e.cause is SseLimitException)
    }

    // ---- 素の ServerSocket によるストリーミング(MockWebServer では作れない動き)

    /** 1 接続だけ受けて、リクエストヘッダを読み、`script` に任せる。 */
    private class RawServer(script: (Socket, List<String>) -> Unit) : AutoCloseable {
        val socket = ServerSocket(0)
        private val worker = thread(isDaemon = true) {
            try {
                socket.accept().use { s ->
                    val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val head = mutableListOf<String>()
                    while (true) {
                        val l = r.readLine() ?: break
                        if (l.isEmpty()) break
                        head += l
                    }
                    script(s, head)
                }
            } catch (_: Exception) {
            }
        }
        val url get() = "http://127.0.0.1:${socket.localPort}"
        override fun close() {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun Socket.write(s: String) {
        getOutputStream().apply { write(s.toByteArray(Charsets.UTF_8)); flush() }
    }

    private val sseHead = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"

    @Test fun eventsAreDeliveredAsTheyArriveAndReturnRightAfterTheTerminalEvent() {
        val release = CountDownLatch(1)
        RawServer { s, _ ->
            s.write(sseHead + ev("state", """{"seq":0,"state":"speech"}""", 0))
            release.await(5, TimeUnit.SECONDS)
            s.write(ev("done", """{"seq":1}""", 1))
            Thread.sleep(5_000) // 終端のあとも接続を開けたままにする(戻りは EOF を待たない)
        }.use { raw ->
            val first = CountDownLatch(1)
            val got = java.util.concurrent.CopyOnWriteArrayList<BridgeEvent>()
            val result = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val t = thread {
                try {
                    client(base = raw.url).events(sid, null) { got += it; if (got.size == 1) first.countDown() }
                } catch (e: Throwable) {
                    result.set(e)
                }
                finished.countDown()
            }
            assertTrue("1 つ目は接続が続いているうちに届く", first.await(3, TimeUnit.SECONDS))
            assertEquals(1, finished.count)
            release.countDown()
            assertTrue("終端イベントのあと、EOF を待たずに戻る", finished.await(3, TimeUnit.SECONDS))
            assertNull(result.get())
            assertEquals(listOf<BridgeEvent>(BridgeEvent.State(0, HostState.SPEECH), BridgeEvent.Done(1)), got.toList())
            t.join(1_000)
        }
    }

    @Test fun keepaliveCommentsKeepTheReadAliveAndSilenceTimesOut() {
        // 読み取りタイムアウト 400 ms に対して 150 ms ごとの keepalive を 1.2 秒続け、そのあと正常に閉じる
        RawServer { s, _ ->
            s.write(sseHead)
            repeat(8) { s.write(": keepalive\n\n"); Thread.sleep(150) }
            s.write(ev("state", """{"seq":0,"state":"waiting"}""", 0))
        }.use { raw ->
            val got = mutableListOf<BridgeEvent>()
            client(base = raw.url, eventsReadMs = 400).events(sid, null) { got += it }
            assertEquals(listOf<BridgeEvent>(BridgeEvent.State(0, HostState.WAITING)), got)
        }
        // 何も来ないと読み取りタイムアウトで NETWORK
        RawServer { s, _ ->
            s.write(sseHead)
            Thread.sleep(3_000)
        }.use { raw ->
            val t0 = System.nanoTime()
            assertEquals(BridgeFailure.NETWORK, failure { client(base = raw.url, eventsReadMs = 300).events(sid, null) { } }.kind)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_500)
        }
    }

    @Test fun halfWrittenEventAtEofIsNotDelivered() {
        RawServer { s, _ ->
            s.write(sseHead + ev("state", """{"seq":0,"state":"speech"}""", 0) + "id: 1\nevent: transcript\ndata: {\"seq\":1,\"te")
        }.use { raw ->
            val got = mutableListOf<BridgeEvent>()
            client(base = raw.url).events(sid, null) { got += it }
            assertEquals(listOf<BridgeEvent>(BridgeEvent.State(0, HostState.SPEECH)), got)
        }
    }

    /**
     * 複数の接続を受ける素のサーバー。最初の接続は `first`(SSE の途中で止まる)、2 本目以降は `routes` が要求行から返す応答を返して閉じる。
     * (JDK の `disconnect()` は Content-Length・chunked の応答を読んでいる最中だとその読みが終わるまで戻らないので、
     * 「読みが止まった SSE」は終端の無い素の応答で再現し、同じインスタンスの後続の通信も同じサーバーで受ける。)
     */
    private class MultiRawServer(first: (Socket) -> Unit, routes: (String) -> ByteArray) : AutoCloseable {
        val socket = ServerSocket(0)
        val requestLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val acceptor = thread(isDaemon = true) {
            var n = 0
            try {
                while (true) {
                    val s = socket.accept()
                    val index = n++
                    thread(isDaemon = true) {
                        try {
                            s.use {
                                val input = it.getInputStream()
                                val head = StringBuilder()
                                while (!head.endsWith("\r\n\r\n")) {
                                    val ch = input.read()
                                    if (ch < 0) return@thread
                                    head.append(ch.toChar())
                                }
                                val lines = head.toString().split("\r\n")
                                requestLines += lines[0]
                                val len = lines.firstOrNull { l -> l.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                                repeat(len) { input.read() }
                                if (index == 0) first(it) else it.getOutputStream().apply { write(routes(lines[0])); flush() }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val url get() = "http://127.0.0.1:${socket.localPort}"
        override fun close() {
            socket.close()
            acceptor.join(2_000)
        }
    }

    private fun rawResponse(type: String, body: ByteArray, code: String = "200 OK") =
        ("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body

    @Test fun abortInFlightEndsABlockedEventsCallAndTheSameClientKeepsWorking() {
        val connected = CountDownLatch(1)
        val sseBody = ev("state", """{"seq":0,"state":"speech"}""", 0) + ev("done", """{"seq":1}""", 1)
        val wav = WavFixtures.valid(ms = 100)
        MultiRawServer(
            first = { s ->
                s.write(sseHead + ": hello\n\n")
                connected.countDown()
                Thread.sleep(10_000)
            },
            routes = { line ->
                when {
                    line.startsWith("POST /v1/voice/sessions ") -> rawResponse("application/json", """{"session_id":"$sid","limits":{}}""".toByteArray(), "201 Created")
                    line.startsWith("POST ") -> rawResponse("application/json", """{"state":"listening","accepted_seq":3}""".toByteArray())
                    line.contains("/events ") -> rawResponse("text/event-stream", sseBody.toByteArray())
                    else -> rawResponse("audio/wav", wav)
                }
            },
        ).use { raw ->
            val c = client(base = raw.url)
            val result = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            thread {
                try {
                    c.events(sid, null) { }
                } catch (e: Throwable) {
                    result.set(e)
                }
                finished.countDown()
            }
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            Thread.sleep(100)
            val t0 = System.nanoTime()
            c.abortInFlight()
            assertTrue("abort で SSE の読みが戻る", finished.await(3, TimeUnit.SECONDS))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
            assertEquals(BridgeFailure.NETWORK, (result.get() as BridgeException).kind)
            // abort のあとも、SSE を中断した同じインスタンスが普通に通信できる(abort 前に始まった呼び出しだけが無効で、あとに始めた呼び出しは有効)
            assertEquals(sid, c.createSession(request).sessionId)
            assertEquals(ChunkAck(AckState.LISTENING, 3), c.sendAudio(sid, 3, ByteArray(640), false))
            c.abortInFlight() // 通信していないときの abort も、そのあとの呼び出しに響かない
            assertEquals(ChunkAck(AckState.LISTENING, 3), c.sendAudio(sid, 3, ByteArray(640), false))
            assertEquals(listOf<BridgeEvent>(BridgeEvent.State(0, HostState.SPEECH), BridgeEvent.Done(1)), collect(c))
            assertEquals(wav.size, c.fetchReplyAudio(sid, 0).size)
            assertEquals(6, raw.requestLines.size)
        }
    }

    @Test fun abortInFlightEndsASendThatNeverGetsAnAnswer() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = client()
        val result = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        thread {
            try {
                c.sendAudio(sid, 1, ByteArray(640), false)
            } catch (e: Throwable) {
                result.set(e)
            }
            finished.countDown()
        }
        req() // サーバーが受け取った
        Thread.sleep(100)
        c.abortInFlight()
        assertTrue("既定の 5 秒の期限より前に戻る", finished.await(2, TimeUnit.SECONDS))
        val e = result.get() as BridgeException
        assertEquals(BridgeFailure.NETWORK, e.kind)
        assertTrue(e.isRetryable)
    }

    // =========================================================================================
    // 中断との競合(登録したあと・通信を始める前の abort)と、1 回の呼び出し = HTTP 要求 1 回

    private enum class HookPoint { AFTER_REGISTER, BEFORE_CONNECT }

    private class HoldHooks(private val point: HookPoint) : BridgeClient.TestHooks {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        private fun hold(p: HookPoint) {
            if (p != point) return
            reached.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        override fun afterRegister(path: String) = hold(HookPoint.AFTER_REGISTER)
        override fun beforeConnect(path: String) = hold(HookPoint.BEFORE_CONNECT)
    }

    /** 呼び出しを `point` で保留し、その間に abort して、保留を解いても要求が 1 つもホストに届かず、`NETWORK`(取消)で終わることを確かめる。 */
    private fun assertAbortedBeforeIoSendsNothing(point: HookPoint, call: (BridgeClient) -> Unit) {
        // 通信が起きたら、これらの応答が使われて見つかる(未使用なら要求は 0)
        repeat(3) { server.enqueue(json("""{"session_id":"$sid","state":"listening","accepted_seq":1}""", 201)) }
        val c = client()
        val hooks = HoldHooks(point)
        c.testHooks = hooks
        val result = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        thread {
            try {
                call(c)
            } catch (e: Throwable) {
                result.set(e)
            }
            finished.countDown()
        }
        assertTrue("保留の位置に達した($point)", hooks.reached.await(3, TimeUnit.SECONDS))
        c.abortInFlight() // 登録済み・通信前の接続に対する abort(未接続の disconnect は何もしない)
        hooks.release.countDown()
        assertTrue("呼び出しが戻る", finished.await(3, TimeUnit.SECONDS))
        val e = result.get()
        assertTrue("BridgeException(NETWORK): $e", e is BridgeException && e.kind == BridgeFailure.NETWORK)
        assertTrue("取消は再送の対象(VoiceSession が abort 後に同じ seq を送り直す)", (e as BridgeException).isRetryable)
        Thread.sleep(150) // 遅れて要求が出ていないことを見る
        assertEquals("abort のあとに通信してはいけない($point): 要求が 0", 0, server.requestCount)
    }

    private fun raceCalls(): Map<String, (BridgeClient) -> Unit> = mapOf(
        "sendAudio" to { c -> c.sendAudio(sid, 1, ByteArray(640), false) },
        "events" to { c -> c.events(sid, null) { } },
        "createSession" to { c -> c.createSession(request) },
        "fetchReplyAudio" to { c -> c.fetchReplyAudio(sid, 0) },
    )

    @Test fun abortRightAfterRegistrationStopsEveryCallBeforeAnyRequest() {
        for ((name, call) in raceCalls()) {
            tearDown(); setUp() // 要求の数を呼び出しごとに数え直す
            try {
                assertAbortedBeforeIoSendsNothing(HookPoint.AFTER_REGISTER, call)
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }
    }

    @Test fun abortJustBeforeConnectStopsEveryCallBeforeAnyRequestIsSent() {
        for ((name, call) in raceCalls()) {
            tearDown(); setUp()
            try {
                assertAbortedBeforeIoSendsNothing(HookPoint.BEFORE_CONNECT, call)
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }
    }

    @Test fun anAbortedCallCannotConnectLaterButACallStartedAfterTheAbortCan() {
        val c = client()
        val hooks = HoldHooks(HookPoint.AFTER_REGISTER)
        c.testHooks = hooks
        val first = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        thread {
            try {
                c.sendAudio(sid, 1, ByteArray(640), false)
            } catch (e: Throwable) {
                first.set(e)
            }
            firstDone.countDown()
        }
        assertTrue(hooks.reached.await(3, TimeUnit.SECONDS))
        c.abortInFlight()
        // 古い呼び出しが保留のまま、abort のあとに始まった別の呼び出しは有効(新しい世代)
        c.testHooks = null
        server.enqueue(json("""{"state":"listening","accepted_seq":2}"""))
        assertEquals(ChunkAck(AckState.LISTENING, 2), c.sendAudio(sid, 2, ByteArray(640), false))
        hooks.release.countDown()
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
        assertEquals(BridgeFailure.NETWORK, (first.get() as BridgeException).kind)
        assertEquals("要求は後の呼び出しの 1 つだけ", 1, server.requestCount)
        assertEquals("2", req().getHeader("X-Chunk-Seq"))
    }

    @Test fun postIsSentExactlyOnceEvenWhenTheConnectionDropsAfterTheRequest() {
        // JDK のバッファ型 POST は通信失敗で自動再送する。ストリーミングで送るので、1 回の呼び出しは HTTP 要求 1 回
        val calls = mapOf<String, (BridgeClient) -> Unit>(
            "sendAudio" to { c -> c.sendAudio(sid, 1, ByteArray(640), false) },
            "createSession" to { c -> c.createSession(request) },
        )
        for ((name, call) in calls) {
            tearDown(); setUp()
            repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            val e = failure { call(client()) }
            assertEquals("$name", BridgeFailure.NETWORK, e.kind)
            assertTrue(e.isRetryable)
            Thread.sleep(150)
            assertEquals("$name: HTTP 要求は 1 回だけ", 1, server.requestCount)
        }
        // 再送は呼び出し側(VoiceSession)が同じ seq で 1 回だけする: 成功するのは次の呼び出し
        tearDown(); setUp()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json("""{"state":"ended","accepted_seq":3}"""))
        val c = client()
        assertEquals(BridgeFailure.NETWORK, failure { c.sendAudio(sid, 3, ByteArray(640), true) }.kind)
        assertEquals(ChunkAck(AckState.ENDED, 3), c.sendAudio(sid, 3, ByteArray(640), true))
        assertEquals(listOf("3", "3"), listOf(req(), req()).map { it.getHeader("X-Chunk-Seq") })
        assertEquals(2, server.requestCount)
    }

    @Test fun streamingPostCarriesLengthAndConnectionCloseAndNeverChunkedEncoding() {
        server.enqueue(json("""{"state":"listening","accepted_seq":1}"""))
        client().sendAudio(sid, 1, ByteArray(1600), false)
        val r = req()
        assertEquals("1600", r.getHeader("Content-Length"))
        assertNull(r.getHeader("Transfer-Encoding"))
        assertEquals("close", r.getHeader("Connection"))
    }

    @Test fun unauthorizedPostInStreamingModeIsAClientErrorWithStatus401() {
        // ストリーミングの POST で 401 を受けると JDK は HttpRetryException を投げる。それを 401 の CLIENT_ERROR にする(再送しない)
        server.enqueue(err(401, "unauthorized"))
        val e = failure { client().sendAudio(sid, 1, ByteArray(640), false) }
        assertEquals(BridgeFailure.CLIENT_ERROR, e.kind)
        assertEquals(401, e.status)
        assertFalse(e.isRetryable)
        server.enqueue(err(401, "unauthorized"))
        val e2 = failure { client().createSession(request) }
        assertEquals(BridgeFailure.CLIENT_ERROR, e2.kind)
        assertEquals(401, e2.status)
        assertFalse("例外メッセージにトークンを含めない", e2.toString().contains(token))
        assertEquals("401 は再送しない", 2, server.requestCount)
        // WWW-Authenticate が付いていても同じ
        server.enqueue(err(401, "unauthorized").setHeader("WWW-Authenticate", "Basic realm=\"x\""))
        assertEquals(401, failure { client().sendAudio(sid, 2, ByteArray(640), false) }.status)
        assertEquals(3, server.requestCount)
    }

    // =========================================================================================
    // 取消(§5.2.5)

    @Test fun cancelSendsDeleteAndAccepts204And404() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(err(404, "not_found"))
        val c = client()
        c.cancel(sid)
        c.cancel(sid)
        val r = req()
        assertEquals("DELETE", r.method)
        assertEquals("/v1/voice/sessions/$sid", r.path)
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertEquals("DELETE", req().method)
        server.enqueue(err(500, "oops"))
        assertEquals(BridgeFailure.SERVER_ERROR, failure { c.cancel(sid) }.kind)
        server.enqueue(err(401, "unauthorized"))
        assertEquals(BridgeFailure.CLIENT_ERROR, failure { c.cancel(sid) }.kind)
    }

    @Test fun cancelIsNotInterruptedByAbortInFlight() {
        val c = client()
        // abort のあとに始まる cancel
        c.abortInFlight()
        server.enqueue(MockResponse().setResponseCode(204))
        c.cancel(sid)
        assertEquals("DELETE", req().method)
        // cancel の最中に abort が来ても潰れない(次のセッションの abort で、前の取消が消えない)
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(600, TimeUnit.MILLISECONDS))
        val result = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        thread {
            try {
                c.cancel(sid)
            } catch (e: Throwable) {
                result.set(e)
            }
            finished.countDown()
        }
        req()
        Thread.sleep(100)
        c.abortInFlight()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertNull("abort されずに 204 を受け取る", result.get())
    }

    @Test fun cancelHasItsOwnShortDeadline() {
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        assertEquals(BridgeFailure.NETWORK, failure { client(cancelMs = 300).cancel(sid) }.kind)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    // =========================================================================================
    // 返答音声(§5.2.4)

    private fun wavResponse(bytes: ByteArray, type: String = "audio/wav") =
        MockResponse().setHeader("Content-Type", type).setBody(okio.Buffer().write(bytes))

    @Test fun fetchReplyAudioReturnsTheValidatedWav() {
        val wav = WavFixtures.valid(ms = 600, rate = 24_000)
        server.enqueue(wavResponse(wav))
        val got = client().fetchReplyAudio(sid, 0)
        assertArrayEquals(wav, got)
        val r = req()
        assertEquals("GET", r.method)
        assertEquals("/v1/voice/sessions/$sid/replies/0/audio", r.path)
        assertEquals("audio/wav", r.getHeader("Accept"))
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        // chunked でも、Content-Type に parameter が付いても
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-wav; foo=bar").setChunkedBody(okio.Buffer().write(wav), 700))
        assertArrayEquals(wav, client().fetchReplyAudio(sid, 0))
    }

    @Test fun brokenOrWrongTypedAudioIsMalformed() {
        val wav = WavFixtures.valid()
        val cases = listOf(
            wavResponse("<html>error page</html>".toByteArray()),
            wavResponse(wav, type = "text/html"),
            wavResponse(wav, type = "audio/mpeg"),
            MockResponse().setBody(okio.Buffer().write(wav)), // Content-Type なし
            wavResponse(wav.copyOf(wav.size - 2)),
            wavResponse(WavFixtures.wav(fmtChunk = WavFixtures.fmt(channels = 2))),
            wavResponse(ByteArray(0)),
        )
        for ((i, resp) in cases.withIndex()) {
            server.enqueue(resp)
            assertEquals("case $i", BridgeFailure.MALFORMED, failure { client().fetchReplyAudio(sid, 0) }.kind)
        }
    }

    @Test fun oversizedAudioIsCutOffWhileReadingAndNeverFullyRead() {
        // 宣言(Content-Length)は小さくても chunked で 12 MB 流れてくる: 10 MB で打ち切る。宣言された data が 11 MB でも読む前に断る
        val declared = WavFixtures.wav(dataBytes = 0, declaredData = 11L * 1024 * 1024)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setChunkedBody(okio.Buffer().write(declared + ByteArray(12 * 1024 * 1024)), 64 * 1024))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchReplyAudio(sid, 0) }.kind)
        // Content-Length が 10 MB 超
        server.enqueue(wavResponse(WavFixtures.wav(sampleRate = 48_000, dataBytes = 10 * 1024 * 1024 - 44 + 2)))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchReplyAudio(sid, 0) }.kind)
        // 120 秒超(宣言)
        server.enqueue(wavResponse(WavFixtures.wav(sampleRate = 16_000, dataBytes = 125 * 16_000 * 2)))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchReplyAudio(sid, 0) }.kind)
        // ちょうど 10 MB は通る
        val max = WavFixtures.wav(sampleRate = 48_000, dataBytes = WavValidator.MAX_BYTES - 44)
        server.enqueue(wavResponse(max))
        assertEquals(WavValidator.MAX_BYTES, client().fetchReplyAudio(sid, 0).size)
    }

    @Test fun fetchStatusesAreClassifiedAndRedirectsNotFollowed() {
        server.enqueue(err(404, "not_found"))
        assertEquals(BridgeFailure.NOT_FOUND, failure { client().fetchReplyAudio(sid, 0) }.kind)
        server.enqueue(err(410, "expired"))
        assertEquals(BridgeFailure.EXPIRED, failure { client().fetchReplyAudio(sid, 0) }.kind)
        server.enqueue(err(503, "tts_unavailable"))
        assertEquals(BridgeFailure.SERVER_ERROR, failure { client().fetchReplyAudio(sid, 0) }.kind)
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals("200 以外の成功", BridgeFailure.MALFORMED, failure { client().fetchReplyAudio(sid, 0) }.kind)
    }

    @Test fun slowAudioDownloadIsCutByTheOverallDeadline() {
        val wav = WavFixtures.valid(ms = 400)
        server.enqueue(wavResponse(wav).throttleBody(2_000, 100, TimeUnit.MILLISECONDS))
        val t0 = System.nanoTime()
        assertEquals(BridgeFailure.NETWORK, failure { client(audioMs = 500).fetchReplyAudio(sid, 0) }.kind)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        assertEquals(0, watchdogThreads())
    }

    @Test fun clientToStringHasNoSecretsAndExposesNoUrl() {
        val s = client().toString()
        assertFalse(s.contains(token))
        assertFalse(s.contains(server.hostName))
        // 取り出せる型の toString(SessionInfo / BridgeEvent)も本文を含まない
        assertFalse(SessionInfo("secret-session-id").toString().contains("secret-session-id"))
        assertFalse(BridgeEvent.Transcript(1, "secret words", true).toString().contains("secret"))
    }

    @Test fun watchdogThreadsDoNotLeakAfterNormalCalls() {
        server.enqueue(json("""{"session_id":"$sid"}""", 201))
        server.enqueue(err(409, "ended"))
        server.enqueue(MockResponse().setResponseCode(204))
        val c = client()
        c.createSession(request)
        failure { c.sendAudio(sid, 1, ByteArray(640), false) }
        c.cancel(sid)
        assertEquals(0, watchdogThreads())
    }

    @Suppress("unused")
    private fun recorded(r: RecordedRequest) = r.path
}
