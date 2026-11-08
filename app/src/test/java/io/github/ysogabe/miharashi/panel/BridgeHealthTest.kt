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
import java.util.concurrent.TimeUnit

/** ブリッジの接続テスト(設計書 §5.2.7、AC13 / AC20)。MockWebServer で契約どおりの相手を再現する。 */
class BridgeHealthTest {
    private lateinit var server: MockWebServer
    private val token = "bridge-token-0123456789"
    private val panelToken = "panel-token-ABCDEFGHIJ"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString()
    private fun check(base: String = base(), t: String = token, connectMs: Int = 3_000, readMs: Int = 5_000) =
        BridgeHealth(base, t, connectMs, readMs).check()

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
}
