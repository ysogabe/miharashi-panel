package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** ブリッジの接続テスト(設計書 §5.2.7、AC13 / AC20)。MockWebServer で契約どおりの相手を再現する。 */
class BridgeHealthTest {
    private lateinit var server: MockWebServer
    private val token = "bridge-token-0123456789"
    private val panelToken = "panel-token-ABCDEFGHIJ"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString()
    private fun check(base: String = base(), t: String = token, connectMs: Int = 3_000, readMs: Int = 5_000, totalMs: Long = 8_000) =
        BridgeHealth(base, t, connectMs, readMs, totalMs).check()

    private fun watchdogThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == BridgeHttp.WATCHDOG_THREAD_NAME }

    /** 1 接続だけ受けて、`script` に任せる素の ServerSocket(MockWebServer では作れない動きの再現用)。 */
    private class RawServer(script: (java.net.Socket) -> Unit) : AutoCloseable {
        val socket = ServerSocket(0)
        private val worker = thread(isDaemon = true) {
            try {
                socket.accept().use { script(it) }
            } catch (_: Exception) {
            }
        }
        val url get() = "http://127.0.0.1:${socket.localPort}"
        override fun close() {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun java.net.Socket.readRequestHead() {
        val r = getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (true) if (r.readLine().isNullOrEmpty()) break
    }

    private val okBody = """{"ok":true,"asr":true,"tts":true,"daidai":true}"""

    @Test fun okParsesFlagsAndSendsOnlyTheBridgeBearerToTheFixedPath() {
        server.enqueue(MockResponse().setBody(okBody))
        val r = check(base = server.url("/").toString().trimEnd('/') + "/")
        assertEquals(HealthStatus.OK, r.status)
        assertEquals(true, r.asr)
        assertEquals(true, r.tts)
        assertEquals(true, r.daidai)
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/v1/health", req.path)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertFalse("panel のトークンはどこにも載らない", req.headers.toString().contains(panelToken))
        assertEquals(1, server.requestCount)
    }

    @Test fun pathPrefixInTheUrlIsKept() {
        server.enqueue(MockResponse().setBody(okBody))
        assertEquals(HealthStatus.OK, check(base = server.url("/bridge/").toString()).status)
        assertEquals("/bridge/v1/health", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun okWithSomethingNotReadyIsStillOkButSaysSo() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"asr":false,"tts":true,"daidai":"yes"}"""))
        val r = check()
        assertEquals(HealthStatus.OK, r.status)
        assertEquals(false, r.asr)
        assertNull("真偽値でない項目は null", r.daidai)
        assertTrue(r.message.contains("音声認識"))
        assertFalse(r.message.contains("音声合成"))
    }

    @Test fun unauthorizedFor401And403() {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"code":"unauthorized","message":"secret detail"}}"""))
            val r = check()
            assertEquals(HealthStatus.UNAUTHORIZED, r.status)
            assertFalse("応答の本文は画面に出さない", r.message.contains("secret detail"))
        }
    }

    @Test fun serverErrorsAreBadResponsesWithTheStatusCode() {
        for (code in listOf(500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"code":"asr_unavailable","message":"x"}}"""))
            val r = check()
            assertEquals(HealthStatus.BAD_RESPONSE, r.status)
            assertEquals(code, r.httpCode)
            assertTrue(r.message.contains("HTTP $code"))
        }
    }

    @Test fun redirectsAreNotFollowed() {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody(okBody))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/v1/health").toString()))
            val r = check()
            assertEquals(HealthStatus.BAD_RESPONSE, r.status)
            assertEquals(302, r.httpCode)
            assertEquals("リダイレクト先には接続しない(トークンも送らない)", 0, other.requestCount)
            // 同じホスト内へのリダイレクトも追従しない
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))
            assertEquals(HealthStatus.BAD_RESPONSE, check().status)
            assertEquals(2, server.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test fun bodyOverFourKilobytesIsRejected() {
        val pad = "x".repeat(4096)
        server.enqueue(MockResponse().setBody("""{"ok":true,"pad":"$pad"}"""))
        assertEquals(HealthStatus.BAD_RESPONSE, check().status)
        // 巨大な chunked の本文も、4 KB を超えた時点で打ち切る
        server.enqueue(MockResponse().setChunkedBody("""{"ok":true,"pad":"${"y".repeat(200_000)}"}""", 1024))
        assertEquals(HealthStatus.BAD_RESPONSE, check().status)
    }

    @Test fun bodyOfExactlyFourKilobytesIsAccepted() {
        val head = """{"ok":true,"pad":""""
        val tail = """"}"""
        val body = head + "z".repeat(4096 - head.length - tail.length) + tail
        assertEquals(4096, body.toByteArray().size)
        server.enqueue(MockResponse().setBody(body))
        assertEquals(HealthStatus.OK, check().status)
    }

    @Test fun brokenOrUnexpectedBodiesAreBadResponses() {
        for (body in listOf("", "not json", "[1,2]", """{"ok":false}""", """{"ok":"true"}""", """{"asr":true}""", """{"ok":null}""")) {
            server.enqueue(MockResponse().setBody(body))
            val r = check()
            assertEquals(body, HealthStatus.BAD_RESPONSE, r.status)
            assertFalse(r.message.contains("not json"))
        }
    }

    @Test fun noResponseTimesOutAsUnreachable() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        val r = check(readMs = 300)
        assertEquals(HealthStatus.UNREACHABLE, r.status)
        assertTrue("読み取りタイムアウトで戻る", (System.nanoTime() - started) / 1_000_000 < 4_000)
    }

    @Test fun connectionRefusedAndResetAreUnreachable() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(HealthStatus.UNREACHABLE, check().status)
        val url = base()
        server.shutdown()
        assertEquals(HealthStatus.UNREACHABLE, check(base = url, connectMs = 500).status)
    }

    @Test fun invalidSettingsMakeNoRequest() {
        val cases = listOf(
            "" to token,
            base() to "",
            base() to "   ",
            "ftp://host" to token,
            "http://user:pw@127.0.0.1:1/" to token,
            "http://127.0.0.1:8800/?x=1" to token,
        )
        for ((u, t) in cases) {
            assertEquals("$u / ${t.length}", HealthStatus.NOT_CONFIGURED, check(base = u, t = t).status)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun messagesNeverContainTheTokenOrTheBody() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"asr":true,"tts":true,"daidai":true,"secret":"$token"}"""))
        val ok = check()
        assertFalse(ok.message.contains(token))
        assertFalse(ok.toString().contains(token))
        server.enqueue(MockResponse().setResponseCode(401).setBody(token))
        assertFalse(check().message.contains(token))
    }

    // ---- 不正なトークン(Authorization ヘッダに載せると IllegalArgumentException になる値) ----

    @Test fun invalidTokenCharactersMakeNoRequestAndLeakNothing() {
        val bad = listOf(
            "bridge-token-0123\nX-Evil: 1",
            "bridge-token-0123\r\nX-Evil: 1",
            "bridge-token-\u0000-0123456789",
            "bridge-token-\u001f-0123456789",
            "bridge-token-\u007f-0123456789",
            "bridge token 0123456789",
            "bridge-token-\u30c8\u30fc\u30af\u30f3-0123456789",
        )
        for (t in bad) {
            val r = check(t = t)
            assertEquals(HealthStatus.NOT_CONFIGURED, r.status)
            assertFalse(r.message.contains("bridge"))
            assertFalse(r.toString().contains("bridge-token"))
        }
        assertEquals("通信しない", 0, server.requestCount)
        assertEquals(0, watchdogThreads())
        // 接続を作る処理自体も、例外を投げるなら固定文言で、値を含めない
        for (t in bad) {
            try {
                BridgeHttp.open(base(), "/v1/health", t, 1_000, 1_000)
                org.junit.Assert.fail("open should refuse")
            } catch (e: java.io.IOException) {
                assertEquals("固定文言で値を含まない", "invalid bridge token", e.message)
                assertTrue(e.cause == null)
            }
        }
    }

    // ---- 全体の期限(ウォッチドッグ) ----

    @Test fun headersArrivingAfterTheDeadlineAreUnreachable() {
        server.enqueue(MockResponse().setBody(okBody).setHeadersDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val r = check(readMs = 10_000, totalMs = 500)
        assertEquals(HealthStatus.UNREACHABLE, r.status)
        assertTrue("期限で戻る", (System.nanoTime() - started) / 1_000_000 < 2_500)
        assertEquals(0, watchdogThreads())
    }

    @Test fun headersDribbledForeverAreCutAtTheDeadline() {
        RawServer { s ->
            s.readRequestHead()
            val out = s.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            repeat(100) { // 1 回の読みは 5 秒以内に必ず進むので読み取りタイムアウトでは切れない
                out.write("X-Slow-$it: a\r\n".toByteArray())
                out.flush()
                Thread.sleep(100)
            }
        }.use { raw ->
            val started = System.nanoTime()
            val r = check(base = raw.url, readMs = 5_000, totalMs = 600)
            assertEquals(HealthStatus.UNREACHABLE, r.status)
            assertTrue("期限で戻る", (System.nanoTime() - started) / 1_000_000 < 3_000)
        }
        assertEquals(0, watchdogThreads())
    }

    @Test fun bodyTrickledPastTheDeadlineIsUnreachable() {
        val body = """{"ok":true,"pad":"${"p".repeat(3000)}"}"""
        server.enqueue(MockResponse().setBody(body).throttleBody(20, 50, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        val r = check(readMs = 5_000, totalMs = 700)
        assertEquals(HealthStatus.UNREACHABLE, r.status)
        assertTrue("期限で戻る", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(0, watchdogThreads())
    }

    @Test fun bodyStalledAfterHeadersIsUnreachable() {
        // ヘッダのあと本文の途中で止まる(Content-Length 無し)。JDK の HttpURLConnection は Content-Length 付きの本文を
        // disconnect で止められないことがあるため(Android の OkHttp 実装は call.cancel() で止まる)、ここは close まで読む形で再現する
        RawServer { s ->
            s.readRequestHead()
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{\"ok\":tr".toByteArray())
                flush()
            }
            Thread.sleep(3_000)
        }.use { raw ->
            val started = System.nanoTime()
            val r = check(base = raw.url, readMs = 10_000, totalMs = 500)
            assertEquals(HealthStatus.UNREACHABLE, r.status)
            assertTrue("期限で戻る", (System.nanoTime() - started) / 1_000_000 < 2_500)
        }
        assertEquals(0, watchdogThreads())
    }

    @Test fun eofAfterTheDeadlineIsNeverTreatedAsOk() {
        // 完全で正しい本文を送ったあと、期限の後に接続を閉じる(Content-Length 無し)。EOF が来ても OK にしない
        RawServer { s ->
            s.readRequestHead()
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n$okBody".toByteArray())
                flush()
            }
            Thread.sleep(1_200)
        }.use { raw ->
            val started = System.nanoTime()
            val r = check(base = raw.url, readMs = 5_000, totalMs = 400)
            assertEquals(HealthStatus.UNREACHABLE, r.status)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_150)
        }
        // 閉じる側が先(期限内)なら OK
        RawServer { s ->
            s.readRequestHead()
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n$okBody".toByteArray())
        }.use { raw ->
            assertEquals(HealthStatus.OK, check(base = raw.url, totalMs = 5_000).status)
        }
        assertEquals(0, watchdogThreads())
    }

    @Test fun deadlineExceededResultNeverCarriesTheTokenOrBody() {
        server.enqueue(MockResponse().setBody(okBody).setHeadersDelay(2, TimeUnit.SECONDS))
        val r = check(readMs = 10_000, totalMs = 300)
        assertFalse(r.toString().contains(token))
        assertFalse(r.message.contains(token))
    }

    @Test fun watchdogsDoNotLeakOnNormalOrFailedCalls() {
        repeat(30) {
            server.enqueue(MockResponse().setBody(okBody))
            assertEquals(HealthStatus.OK, check().status)
        }
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(HealthStatus.BAD_RESPONSE, check().status)
        }
        repeat(5) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertEquals(HealthStatus.UNREACHABLE, check().status)
        }
        assertEquals("ウォッチドッグのスレッドが残らない", 0, watchdogThreads())
    }

    @Test fun defaultDeadlineIsEightSeconds() {
        assertEquals(8_000L, BridgeHttp.TOTAL_TIMEOUT_MS)
    }
}
