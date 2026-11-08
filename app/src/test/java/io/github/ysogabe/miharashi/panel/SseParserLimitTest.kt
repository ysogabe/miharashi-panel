package io.github.ysogabe.miharashi.panel

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.StringReader

/** `SseParser` の `id:` の保持と、1 行 8 KB・1 イベント 16 KB の上限(設計書 §5.8)。 */
class SseParserLimitTest {
    private fun feed(p: SseParser, vararg lines: String): SseEvent? {
        var last: SseEvent? = null
        for (l in lines) p.next(l)?.let { last = it }
        return last
    }

    @Test
    fun keepsIdOnTheEvent() {
        val p = SseParser()
        val e = feed(p, "id: 7", "event: state", "data: {\"seq\":7}", "")
        assertEquals(SseEvent("state", "{\"seq\":7}", "7"), e)
        assertEquals("7", p.lastEventId)
    }

    @Test
    fun idAbsentGivesNullAndDoesNotCarryOver() {
        val p = SseParser()
        assertEquals("1", feed(p, "id: 1", "data: a", "")?.id)
        val second = feed(p, "data: b", "")
        assertNull(second?.id)
        assertEquals("イベントに無くても最後の id は残る", "1", p.lastEventId)
    }

    @Test
    fun idWithoutDataStillUpdatesLastEventIdButDispatchesNothing() {
        val p = SseParser()
        assertNull(feed(p, "id: 9", ""))
        assertEquals("9", p.lastEventId)
    }

    @Test
    fun idRulesFollowSse() {
        val p = SseParser()
        assertEquals("", feed(p, "id:", "data: x", "")?.id)
        assertEquals("", p.lastEventId)
        assertEquals("a b", feed(p, "id:a b", "data: x", "")?.id)
        assertNull("NUL を含む id は無視", feed(p, "id: a\u0000b", "data: x", "")?.id)
        assertEquals("last", feed(p, "id: first", "id: last", "data: x", "")?.id)
        assertNull("コメント行は読まない", feed(p, ": id: no", "data: x", "")?.id)
    }

    @Test
    fun legacyLineApiStillReturnsPairs() {
        val p = SseParser()
        assertNull(p.line("id: 3"))
        assertNull(p.line("event: status"))
        assertNull(p.line("data: a"))
        assertEquals("status" to "a", p.line(""))
        assertEquals("3", p.lastEventId)
    }

    @Test
    fun lineLimitIsEightKilobytesOfUtf8() {
        val p = SseParser()
        val ok = "data: " + "a".repeat(SseParser.MAX_LINE_BYTES - 6)
        assertEquals(SseParser.MAX_LINE_BYTES, ok.length)
        assertNull(p.next(ok))
        try {
            SseParser().next(ok + "a")
            fail("1 バイト超過")
        } catch (e: SseLimitException) {
            assertFalse("受信内容を含めない", e.message.orEmpty().contains("aaaa"))
        }
        // 日本語は 1 文字 3 バイト
        val jp = "data: " + "あ".repeat((SseParser.MAX_LINE_BYTES - 6) / 3)
        assertNull(SseParser().next(jp))
        try {
            SseParser().next(jp + "あ")
            fail("バイトで数える")
        } catch (_: SseLimitException) { }
        // 長い行はコメントでも超過
        try {
            SseParser().next(":" + "k".repeat(SseParser.MAX_LINE_BYTES))
            fail("コメント行も対象")
        } catch (_: SseLimitException) { }
    }

    @Test
    fun eventLimitIsSixteenKilobytes() {
        val p = SseParser()
        val chunk = "data: " + "a".repeat(4_000) // 4006 バイト + 改行 1
        var sent = 0
        try {
            while (true) {
                p.next(chunk)
                sent++
                if (sent > 10) fail("上限に達しない")
            }
        } catch (_: SseLimitException) { }
        assertEquals("4007 バイトずつで 4 行(16028)目まで、5 行目で超過", 4, sent)
        // ちょうど上限は通る
        val q = SseParser()
        val line = "data: " + "a".repeat(8_192 - 6 - 1)
        assertNull(q.next(line))
        assertNull(q.next(line)) // 合計 8192 + 8192 = 16384 = 上限ちょうど
        assertEquals("a".repeat(8_185) + "\n" + "a".repeat(8_185), q.next("")?.data)
        val r = SseParser()
        r.next(line)
        r.next(line)
        try {
            r.next("id:")
            fail("1 バイトでも超えたら超過")
        } catch (_: SseLimitException) { }
    }

    @Test
    fun limitResetsBetweenEvents() {
        val p = SseParser()
        repeat(20) {
            assertNull(p.next("data: " + "a".repeat(8_000)))
            assertTrue(p.next("")!!.data.length == 8_000)
        }
    }

    @Test
    fun commentsDoNotCountTowardsEvent() {
        val p = SseParser()
        assertNull(p.next("data: x"))
        repeat(100) { assertNull(p.next(": keepalive " + "k".repeat(1_000))) }
        assertEquals("x", p.next("")?.data)
    }

    // ---- BoundedLineReader ----

    @Test
    fun boundedReaderSplitsLikeBufferedReader() {
        val r = BoundedLineReader(StringReader("a\nb\r\nc\rd\n\ne"), 100)
        assertEquals(listOf("a", "b", "c", "d", "", "e"), generateSequence { r.readLine() }.toList())
        assertNull(BoundedLineReader(StringReader(""), 10).readLine())
        assertEquals(listOf("x", ""), BoundedLineReader(StringReader("x\n\n"), 10).let { r2 -> generateSequence { r2.readLine() }.toList() })
    }

    @Test
    fun boundedReaderStopsBeforeReadingAnOverlongLine() {
        val src = object : java.io.Reader() {
            var read = 0L
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                for (i in 0 until len) cbuf[off + i] = 'x'
                read += len
                return len
            }
            override fun close() {}
        }
        try {
            BoundedLineReader(java.io.BufferedReader(src), 8_192).readLine()
            fail("上限超過")
        } catch (_: SseLimitException) { }
        assertTrue("無限の行を読み続けない: ${src.read}", src.read < 100_000)
    }

}

/** `KioskClient.events` が SSE の上限で接続を終える。 */
class KioskClientSseLimitTest {
        private lateinit var server: MockWebServer
        private lateinit var client: KioskClient

        @Before fun setUp() {
            server = MockWebServer().also { it.start() }
            client = KioskClient(server.url("/").toString(), "test-token-0123456789")
        }

        @After fun tearDown() = server.shutdown()

        private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

        @Test fun overlongLineEndsTheConnectionWithAnIoException() {
            val ok = "event: status\ndata: ${fixture("status-photo.json").trim()}\n\n"
            server.enqueue(sse(ok + "data: " + "a".repeat(9_000) + "\n\n" + ok))
            val got = mutableListOf<String?>()
            try {
                client.events { got += it.currentScreen?.id }
                fail("上限超過")
            } catch (e: java.io.IOException) {
                assertTrue(e is SseLimitException)
            }
            assertEquals("上限の前のイベントは届く", listOf("photo"), got)
        }

        @Test fun overlongEventEndsTheConnection() {
            val lines = (1..6).joinToString("") { "data: " + "a".repeat(3_000) + "\n" }
            server.enqueue(sse("event: status\n$lines\n"))
            try {
                client.events { fail("届かない") }
                fail("上限超過")
            } catch (_: SseLimitException) { }
        }

        @Test fun normalStreamIsUnaffected() {
            val body = ": keepalive\n\nid: 1\nevent: status\ndata: ${fixture("status-details.json").trim()}\n\n" +
                "event: other\ndata: ignored\n\nevent: status\r\ndata: ${fixture("status-photo.json").trim()}\r\n\r\n"
            server.enqueue(sse(body))
            val got = mutableListOf<String?>()
            client.events { got += it.currentScreen?.id }
            assertEquals(listOf("status", "photo"), got)
        }
    }
