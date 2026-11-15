package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class DaidaiLayoutTest {
    private fun boxes(l: DaidaiLayout): Map<String, Box> = buildMap {
        put("header", l.header)
        put("avatar", l.avatar)
        l.card?.let { put("card", it) }
        put("chip", l.chip)
        put("transcript", l.transcript)
        put("reply", l.reply)
    }

    private fun overlaps(a: Box, b: Box) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun eachSize(block: (DaidaiLayout) -> Unit) {
        for ((w, h) in listOf(960f to 480f, 1920f to 960f, 480f to 240f, 1024f to 600f, 800f to 600f))
            for (card in listOf(false, true)) block(DaidaiLayout(w, h, card))
    }

    @Test fun everythingFitsInTheScreen() = eachSize { l ->
        val all = boxes(l) + ("guide" to l.guide) + ("mic" to l.micBadge) + ("dots" to l.dotsArea)
        for ((name, b) in all) {
            assertTrue("$name $b", b.left >= -0.01f && b.top >= -0.01f && b.right <= l.width + 0.01f && b.bottom <= l.height + 0.01f)
            assertTrue(name, b.width > 0 && b.height > 0)
        }
    }

    @Test fun nothingOverlapsAndNothingTouchesThePageDots() = eachSize { l ->
        val m = boxes(l)
        val names = m.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size)
            assertFalse("${names[i]} / ${names[j]} (card=${l.hasCard})", overlaps(m.getValue(names[i]), m.getValue(names[j])))
        for ((name, b) in m) assertTrue("$name above the dots", b.bottom <= l.dotsArea.top + 0.01f)
        assertFalse(overlaps(l.guide, l.avatar))
        assertFalse(overlaps(l.guide, l.header))
        assertTrue(l.guide.bottom <= l.dotsArea.top + 0.01f)
        // マイク使用中はヘッダーの中
        assertTrue(l.micBadge.left >= l.header.left && l.micBadge.right <= l.header.right)
        assertTrue(l.micBadge.top >= l.header.top && l.micBadge.bottom <= l.header.bottom)
    }

    @Test fun headerIsTheSameAsScreenA() {
        for ((w, h) in listOf(960f to 480f, 1024f to 600f)) assertEquals(PanelLayout(w, h).header, DaidaiLayout(w, h, false).header)
    }

    @Test fun scalesLikePanelLayout() {
        val a = DaidaiLayout(960f, 480f, true)
        val b = DaidaiLayout(1920f, 960f, true)
        assertEquals(1f, a.scale, 1e-6f)
        assertEquals(2f, b.scale, 1e-6f)
        assertEquals(a.reply.left * 2, b.reply.left, 1e-3f)
        assertEquals(a.avatar.height * 2, b.avatar.height, 1e-3f)
        // 縦長にはみ出さず、中央に寄る
        val c = DaidaiLayout(960f, 600f, true)
        assertEquals(1f, c.scale, 1e-6f)
        assertEquals(60f, c.avatar.top - a.avatar.top, 1e-3f)
    }

    @Test fun cardOnlyWhenThereIsANotice() {
        assertNull(DaidaiLayout(960f, 480f, false).card)
        assertNotNull(DaidaiLayout(960f, 480f, true).card)
        // カードがないときは右の列を上へ詰める
        assertTrue(DaidaiLayout(960f, 480f, false).chip.top < DaidaiLayout(960f, 480f, true).chip.top)
    }

    @Test fun replyAreaHoldsFourLinesAtTheLargestSize() = eachSize { l ->
        val need = DaidaiLayout.REPLY_SIZES.first() * DaidaiLayout.LINE_HEIGHT * DaidaiLayout.REPLY_MAX_LINES + 2 * 14f
        assertTrue("need $need have ${l.reply.height / l.scale}", l.reply.height / l.scale >= need)
    }
}
