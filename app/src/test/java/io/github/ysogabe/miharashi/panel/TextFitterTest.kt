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
}
