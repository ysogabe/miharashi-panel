package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class KioskClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: KioskClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KioskClient(server.url("/").toString(), TOKEN)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun statusSendsBearerAndParses() {
        server.enqueue(MockResponse().setBody(fixture("status-photo.json")))
        val s = client.status()
        assertEquals("photo", s.currentScreen?.id)
        val req = server.takeRequest()
        assertEquals("/api/v1/status", req.path)
        assertEquals("Bearer $TOKEN", req.getHeader("Authorization"))
    }

    @Test
    fun inputPostsKeyAndAccepts204() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.input("next")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/input", req.path)
        assertEquals("""{"key":"next"}""", req.body.readUtf8())
    }

    @Test
    fun displayPostsMode() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.display("force_off")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/display", req.path)
        assertEquals("""{"mode":"force_off"}""", req.body.readUtf8())
        assertEquals("Bearer $TOKEN", req.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Home Assistant を切り替えられない: timeout"}"""))
        try {
            client.display("auto")
            fail("502 は例外")
        } catch (e: KioskHttpException) {
            assertEquals(502, e.code)
            assertTrue(e.message!!, e.message!!.contains("Home Assistant"))
        }
    }

    @Test
    fun errorsCarryTheKioskMessage() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"up は PoC-003(#5)まで未対応。使えるのは prev / next / select"}"""),
        )
        try {
            client.input("up")
            fail("400 は例外")
        } catch (e: KioskHttpException) {
            assertEquals(400, e.code)
            assertTrue(e.message!!, e.message!!.startsWith("HTTP 400 up は"))
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Authorization: Bearer <token> が必要"}"""))
        try {
            client.status()
            fail("401 は例外")
        } catch (e: KioskHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun eventsDeliverEachStatusAndSkipKeepalive() {
        val body = ": keepalive\n\n" +
            "event: status\ndata: ${fixture("status-details.json").trim()}\n\n" +
            ": keepalive\n\n" +
            "event: status\ndata: ${fixture("status-photo.json").trim()}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val got = mutableListOf<String?>()
        client.events { got += it.currentScreen?.id }
        assertEquals(listOf("status", "photo"), got)
        assertEquals("Bearer $TOKEN", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization"))
    }

    @Test
    fun unreachableKioskFailsFast() {
        server.shutdown()
        val c = KioskClient(server.url("/").toString(), TOKEN, connectTimeoutMs = 500, readTimeoutMs = 500)
        val start = System.nanoTime()
        try {
            c.status()
            fail("接続できない")
        } catch (e: java.io.IOException) {
            assertTrue(KioskSync.describe(e).isNotEmpty())
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
    }

    companion object {
        const val TOKEN = "test-token-0123456789"
    }
}

class SseParserTest {
    @Test
    fun joinsDataLinesAndResetsEventName() {
        val p = SseParser()
        assertNull(p.line("event: status"))
        assertNull(p.line("data: a"))
        assertNull(p.line("data: b"))
        assertEquals("status" to "a\nb", p.line(""))
        assertNull(p.line(": keepalive"))
        assertNull(p.line(""))
        assertNull(p.line("data:x"))
        assertEquals("message" to "x", p.line(""))
    }
}

class BackoffTest {
    @Test
    fun doublesUpToThirtySecondsAndResets() {
        val b = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.nextDelayMs() })
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }
}
