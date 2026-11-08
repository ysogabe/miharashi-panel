package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class GraphemesTest {
    private fun split(s: String): List<String> {
        var start = 0
        return Graphemes.ends(s).map { end -> s.substring(start, end).also { start = end } }
    }

    @Test fun plainAndSurrogatePairs() {
        assertEquals(listOf("a", "あ", "\uD83D\uDE00"), split("aあ\uD83D\uDE00"))
        assertTrue(split("").isEmpty())
    }

    @Test fun flagsAreTwoRegionalIndicatorsEach() {
        val jp = "\uD83C\uDDEF\uD83C\uDDF5"
        val us = "\uD83C\uDDFA\uD83C\uDDF8"
        assertEquals(listOf(jp, us, jp), split(jp + us + jp))
        // 奇数個の最後は単独
        assertEquals(listOf(jp, "\uD83C\uDDFA"), split(jp + "\uD83C\uDDFA"))
    }

    @Test fun skinToneAndZwjAndVariationSelector() {
        val thumbs = "\uD83D\uDC4D\uD83C\uDFFD"
        assertEquals(listOf(thumbs), split(thumbs))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        assertEquals(listOf(family, "a"), split(family + "a"))
        val heart = "\u2764\uFE0F" // ❤️
        assertEquals(listOf(heart, heart), split(heart + heart))
        val couple = "\uD83D\uDC69\uD83C\uDFFB\u200D\u2764\uFE0F\u200D\uD83D\uDC68\uD83C\uDFFF" // 肌色つき ZWJ 列
        assertEquals(listOf(couple), split(couple))
        val keycap = "1\uFE0F\u20E3"
        assertEquals(listOf(keycap), split(keycap))
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F" // タグ付きの旗
        assertEquals(listOf(england), split(england))
    }

    @Test fun combiningMarksAttachToTheBase() {
        assertEquals(listOf("e\u0301", "x"), split("e\u0301x"))
        assertEquals(listOf("\u304B\u3099", "\u304D"), split("\u304B\u3099\u304D"))
        assertEquals(listOf("a\u0300\u0301\u0302"), split("a\u0300\u0301\u0302"))
    }

    @Test fun zwjBetweenNonPictographsDoesNotJoinTheNextCharacter() {
        assertEquals(listOf("a\u200D", "b"), split("a\u200Db"))
    }

    @Test fun lineBreaksAndControlsAreSeparate() {
        assertEquals(listOf("a", "\r\n", "b"), split("a\r\nb"))
        assertEquals(listOf("a", "\n", "\u0301"), split("a\n\u0301"))
    }
}
