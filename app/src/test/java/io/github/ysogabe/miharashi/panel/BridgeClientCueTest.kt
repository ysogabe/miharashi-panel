package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 挨拶の口(`GET /v1/voice/cues`・`GET /v1/voice/cues/{name}`。段 G)の通信の試験 */
class BridgeClientCueTest {
    private lateinit var server: MockWebServer
    private val token = "bridge-token-0123456789"
    private val sha = "a5626831ccd6647143af5ce2da85d22ddf0baa57a76f9b7ebadbbbd1a6bb9f49"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = BridgeClient(server.url("/").toString().trimEnd('/'), token)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private fun wav(ms: Int, rate: Int = 48_000) = StartChime.wrap(ShortArray(rate * ms / 1000) { 500 }, rate)

    private inline fun failure(block: () -> Unit): BridgeException {
        try {
            block()
        } catch (e: BridgeException) {
            return e
        }
        fail("BridgeException が投げられるはず")
        throw AssertionError()
    }

    @Test fun theManifestIsReadFromTheFixedPathWithTheBridgeBearer() {
        // ホストの本文(ensure_ascii の \u の書き方を含む)
        server.enqueue(
            json(
                """{"voice": "panel-20261116-k5", "cues": {"g_morning_swipe": {"sha256": "$sha", "ms": 2520, """ +
                    """"text": "おはよう", "scene": "swipe", "daypart": "morning"}, "g_retry": {"sha256": "$sha", "ms": 1280, "text": "はい、どうぞ。", "scene": "retry", "daypart": null}}}""",
            ),
        )
        val m = client().fetchCueManifest()
        assertEquals("panel-20261116-k5", m.voice)
        assertEquals(CueEntry(sha, 2520), m.cues["g_morning_swipe"])
        assertEquals(CueEntry(sha, 1280), m.cues["g_retry"])
        val r = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("GET", r.method)
        assertEquals("/v1/voice/cues", r.path)
        assertEquals("Bearer $token", r.getHeader("Authorization"))
    }

    @Test fun malformedEntriesAreDropped() {
        server.enqueue(
            json(
                """{"cues": {"g_ok": {"sha256": "$sha", "ms": 1000}, "../x": {"sha256": "$sha", "ms": 1000}, "G_UP": {"sha256": "$sha", "ms": 1000},
                   "g_bad_sha": {"sha256": "${sha.uppercase()}", "ms": 1000}, "g_short": {"sha256": "abc", "ms": 1000},
                   "g_long": {"sha256": "$sha", "ms": 5001}, "g_zero": {"sha256": "$sha", "ms": 0}, "g_str": {"sha256": "$sha", "ms": "1000"},
                   "g_frac": {"sha256": "$sha", "ms": 1000.5}, "g_notobj": 3}}""",
            ),
        )
        val m = client().fetchCueManifest()
        assertEquals(setOf("g_ok"), m.cues.keys)
    }

    @Test fun aManifestOver16KbOrNotJsonIsMalformed() {
        server.enqueue(json("""{"cues": {}, "pad": "${"x".repeat(VoiceLimits.CUE_MANIFEST_MAX_BYTES)}"}"""))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchCueManifest() }.kind)
        server.enqueue(json("not json"))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchCueManifest() }.kind)
        server.enqueue(json("""{"voice": "v"}"""))
        assertEquals("cues が無い", BridgeFailure.MALFORMED, failure { client().fetchCueManifest() }.kind)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("""{"cues": {}}"""))
        assertEquals(BridgeFailure.MALFORMED, failure { client().fetchCueManifest() }.kind)
    }

    @Test fun anOldHostReturnsNotFound() {
        server.enqueue(json("""{"error":{"code":"not_found","message":"m"}}""", 404))
        assertEquals(BridgeFailure.NOT_FOUND, failure { client().fetchCueManifest() }.kind)
    }

    @Test fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.invalid/v1/voice/cues"))
        assertEquals(BridgeFailure.CLIENT_ERROR, failure { client().fetchCueManifest() }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun aCueWavIsFetchedByItsKnownNameAndValidated() {
        val w = wav(1_000)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody(Buffer().write(w)))
        assertArrayEquals(w, client().fetchCue("g_plain_notice"))
        val r = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/v1/voice/cues/g_plain_notice", r.path)
        assertEquals("Bearer $token", r.getHeader("Authorization"))
    }

    @Test fun unknownNamesAreNeverRequested() {
        for (name in listOf("g_evil", "../sessions", "g_retry/../x", "", "G_RETRY")) {
            assertEquals(BridgeFailure.MALFORMED, failure { client().fetchCue(name) }.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun aBadCueResponseIsRejected() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(Buffer().write(wav(500))))
        assertEquals("audio/wav でない", BridgeFailure.MALFORMED, failure { client().fetchCue("g_retry") }.kind)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody("RIFFnope"))
        assertEquals("WAV でない", BridgeFailure.MALFORMED, failure { client().fetchCue("g_retry") }.kind)
        val big = ByteArray(VoiceLimits.CUE_MAX_BYTES + 1)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody(Buffer().write(big)))
        assertEquals("1 MB 超", BridgeFailure.MALFORMED, failure { client().fetchCue("g_retry") }.kind)
        server.enqueue(json("""{"error":{"code":"not_found","message":"m"}}""", 404))
        assertEquals(BridgeFailure.NOT_FOUND, failure { client().fetchCue("g_retry") }.kind)
    }

    @Test fun cueFetchesAreNotCutBySessionAborts() {
        // 中断の対象にしない接続: abortInFlight のあとでもそのまま通信できる(セッションの終わりに巻き込まれない)
        val c = client()
        c.abortInFlight()
        server.enqueue(json("""{"cues": {}}"""))
        assertTrue(c.fetchCueManifest().cues.isEmpty())
        assertFalse(c.toString().contains(token))
    }
}
