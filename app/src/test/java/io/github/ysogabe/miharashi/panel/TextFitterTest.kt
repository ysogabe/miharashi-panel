package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class TextFitterTest {
    /** 全角は size、半角は size/2 の幅 */
    private fun measure(size: Float, s: String): Float =
        s.codePoints().toArray().sumOf { (if (it < 0x100) 0.5 else 1.0) * size }.toFloat()

    private val sizes = listOf(28f, 24f, 21f, 18f)

    @Test fun shortTextStaysAtTheLargestSizeOnOneLine() {
        val fit = TextFitter.fit("今日は晴れです", 600f, 4, sizes, ::measure)
        assertEquals(28f, fit.size)
        assertEquals(listOf("今日は晴れです"), fit.lines)
        assertFalse(fit.truncated)
    }

    @Test fun wrapsAtTheWidthAndKeepsNewlines() {
        val w = TextFitter.wrap("あいうえおかきくけこ", 50f, 4) { measure(10f, it) }
        assertEquals(listOf("あいうえお", "かきくけこ"), w.lines)
        assertFalse(w.more)
        assertEquals(listOf("あ", "い"), TextFitter.wrap("あ\nい", 500f, 4) { measure(10f, it) }.lines)
    }

    @Test fun shrinksBeforeTruncating() {
        // 600px 幅・4 行で、28px だと 21 字/行=84 字、18px だと 33 字/行=132 字
        val text = "あ".repeat(120)
        val fit = TextFitter.fit(text, 600f, 4, sizes, ::measure)
        assertEquals(18f, fit.size)
        assertFalse(fit.truncated)
        assertEquals(4, fit.lines.size)
    }

    @Test fun veryLongTextEndsWithAnEllipsisInFourLines() {
        val fit = TextFitter.fit("あ".repeat(2000), 600f, 4, sizes, ::measure)
        assertEquals(18f, fit.size)
        assertTrue(fit.truncated)
        assertEquals(4, fit.lines.size)
        assertTrue(fit.lines.last().endsWith("…"))
        for (l in fit.lines) assertTrue(measure(fit.size, l) <= 600f)
    }

    @Test fun manyNewlinesStillFitInFourLines() {
        val fit = TextFitter.fit("a\n".repeat(500).trim(), 600f, 4, sizes, ::measure)
        assertEquals(4, fit.lines.size)
        assertTrue(fit.truncated)
        assertTrue(fit.lines.last().endsWith("…"))
    }

    @Test fun breaksLatinTextAtSpaces() {
        val w = TextFitter.wrap("hello world foo", 110f, 4) { measure(20f, it) } // 1 文字 10px、11 文字/行
        assertEquals(listOf("hello world", "foo"), w.lines)
        val w2 = TextFitter.wrap("hello wonderful", 100f, 4) { measure(20f, it) }
        assertEquals("hello", w2.lines.first())
    }

    @Test fun aGlyphWiderThanTheLineDoesNotLoop() {
        val w = TextFitter.wrap("あいう", 5f, 10) { measure(10f, it) }
        assertEquals(listOf("あ", "い", "う"), w.lines)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val text = "😀".repeat(100)
        val fit = TextFitter.fit(text, 100f, 2, listOf(20f)) { size, s -> s.codePointCount(0, s.length) * size }
        assertTrue(fit.truncated)
        for (l in fit.lines) assertTrue(l.codePoints().toArray().none { it in 0xD800..0xDFFF })
        assertTrue(fit.lines.last().endsWith("…"))
    }

    @Test fun ellipsizeSingleLine() {
        assertEquals("あいう", TextFitter.ellipsize("あいう", 40f) { measure(10f, it) })
        assertEquals("あい…", TextFitter.ellipsize("あいうえお", 30f) { measure(10f, it) })
    }

    @Test fun emptyTextHasNoLines() {
        val fit = TextFitter.fit("", 100f, 4, sizes, ::measure)
        assertTrue(fit.lines.isEmpty())
        assertFalse(fit.truncated)
    }

    // ---- 書記素クラスタ(国旗・肌色・ZWJ・結合文字)を分けない

    private val flag = "\uD83C\uDDEF\uD83C\uDDF5" // 🇯🇵
    private val thumbs = "\uD83D\uDC4D\uD83C\uDFFD" // 👍🏽
    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧
    private val accent = "e\u0301" // é(結合)
    private val dakuten = "\u304B\u3099" // か + 結合濁点
    private val clusters = listOf(flag, thumbs, family, accent, dakuten, "\uD83D\uDE00")

    /** クラスタ 1 つを幅 10 とする */
    private fun clusterWidth(s: String) = Graphemes.ends(s).size * 10f

    @Test fun wrapNeverSplitsAClusterAcrossLines() {
        for (c in clusters) {
            val text = c.repeat(7)
            val w = TextFitter.wrap(text, 30f, 10, ::clusterWidth)
            assertEquals(listOf(c.repeat(3), c.repeat(3), c), w.lines)
            assertFalse(w.more)
        }
    }

    @Test fun wrapKeepsRegionalIndicatorPairsAligned() {
        // 国旗 3 つ(地域指示記号 6 つ)。1 行 2 つなら、対は常に旗として残る
        val flags = flag + "\uD83C\uDDFA\uD83C\uDDF8" + "\uD83C\uDDEB\uD83C\uDDF7"
        val w = TextFitter.wrap(flags, 20f, 5, ::clusterWidth)
        assertEquals(2, w.lines.size)
        assertEquals(flag + "\uD83C\uDDFA\uD83C\uDDF8", w.lines[0])
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7", w.lines[1])
    }

    @Test fun ellipsizeNeverCutsInsideACluster() {
        for (c in clusters) {
            assertEquals(c.repeat(2) + "…", TextFitter.ellipsize(c.repeat(5), 30f, measure = ::clusterWidth))
        }
        // 幅が「…」ぎりぎりでも、クラスタの断片は残らない
        val out = TextFitter.ellipsize(family.repeat(3), 10f, measure = ::clusterWidth)
        assertEquals("…", out)
    }

    @Test fun fitTruncatesAtAClusterBoundaryWithAnEllipsis() {
        for (c in clusters) {
            val fit = TextFitter.fit(c.repeat(100), 100f, 2, listOf(20f)) { _, s -> clusterWidth(s) }
            assertTrue(fit.truncated)
            assertEquals(2, fit.lines.size)
            val last = fit.lines.last()
            assertTrue(last.endsWith("…"))
            val body = last.removeSuffix("…")
            assertEquals(body, c.repeat(body.length / c.length)) // 完全なクラスタの繰り返しだけ
            assertEquals(c.repeat(10), fit.lines.first())
        }
    }

    @Test fun twoThousandCharactersOfClustersIsFast() {
        val text = (family + flag + thumbs + dakuten + "あ").repeat(2000 / 5)
        val t0 = System.nanoTime()
        repeat(5) { TextFitter.fit(text, 600f, 4, sizes, ::measure) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took ${ms}ms", ms < 2000)
    }
}
