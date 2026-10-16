package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    private val l = PanelLayout(960f, 480f)

    @Test
    fun mainKeysSendPrevSelectNext() {
        assertEquals(listOf(Icon.PREV, Icon.SELECT, Icon.NEXT), l.mainKeys.map { it.icon })
        assertEquals(listOf("prev", "select", "next").map(Action::Key), l.mainKeys.map { it.action })
        assertTrue(l.mainKeys.all { it.enabled })
    }

    @Test
    fun sideKeysSendUpDownMenu() {
        assertEquals(listOf(Icon.UP, Icon.DOWN, Icon.MENU), l.sideKeys.map { it.icon })
        assertEquals(listOf("up", "down", "menu").map(Action::Key), l.sideKeys.map { it.action })
        assertTrue(l.sideKeys.all { it.enabled })
    }

    @Test
    fun monitorKeysSwitchTheKioskDisplay() {
        assertEquals(listOf("消灯", "点灯", "自動"), l.displayKeys.map { it.label })
        assertEquals(listOf("force_off", "force_on", "auto").map(Action::Display), l.displayKeys.map { it.action })
        // キーのボタンから離した左のレールに、状態の下・設定の上に縦に並べる(押し間違えないように)
        assertTrue(l.displayKeys.all { it.box.right <= l.rail.right && it.box.right + 24 <= l.mainKeys[0].box.left })
        assertTrue(l.displayKeys.all { it.box.top >= l.monitorStatus.bottom && it.box.bottom < l.settings.box.top })
        assertTrue(l.displayKeys.all { it.box.height >= 64 })
    }

    @Test
    fun hitFindsTheButtonUnderTheFinger() {
        for (b in l.buttons) assertEquals(b.id, l.hit(b.box.centerX, b.box.centerY)?.id)
        assertNull("ヘッダーはボタンではない", l.hit(480f, 20f))
    }

    @Test
    fun buttonsDoNotOverlapAndStayOnScreen() {
        val bs = l.buttons
        for (a in bs) {
            assertTrue(a.id, a.box.left >= 0 && a.box.top >= 0 && a.box.right <= 960 && a.box.bottom <= 480)
            for (b in bs) {
                if (a === b) continue
                val overlap = a.box.left < b.box.right && b.box.left < a.box.right &&
                    a.box.top < b.box.bottom && b.box.top < a.box.bottom
                assertFalse("${a.id} と ${b.id}", overlap)
            }
        }
    }

    @Test
    fun rotationRowSitsAboveTheKeysWithoutOverlapping() {
        val r = l.rotationRow
        assertTrue("中央に収まる", r.left >= l.center.left && r.right <= l.center.right)
        assertTrue("キーのボタンから離す", r.bottom + 12 <= l.mainKeys.minOf { it.box.top })
        for (b in l.buttons) {
            val overlap = r.left < b.box.right && b.box.left < r.right && r.top < b.box.bottom && b.box.top < r.bottom
            assertFalse(b.id, overlap)
        }
        assertNull("ボタンの判定には入れない(PanelView が巡回の状態で決める)", l.hit(r.centerX, r.centerY))
        assertTrue("指で押せる高さ", r.height >= 40)
    }

    @Test
    fun mainKeysAreLargeEnoughToTap() {
        // 960×480・195dpi の Echo Show 5 で 1 辺 1.8cm 以上(指先で外さない大きさ)
        assertTrue(l.mainKeys.all { it.box.width >= 140 && it.box.height >= 140 })
    }

    @Test
    fun scalesAndCentersOnOtherSizes() {
        val big = PanelLayout(1920f, 1080f)
        assertEquals(2f, big.scale)
        assertEquals(60f, big.header.top)
        assertEquals(big.mainKeys[1].id, big.hit(960f, big.mainKeys[1].box.centerY)?.id)
    }

    @Test
    fun menuRowsFitAboveTheKeysAndKeepTheSelectionVisible() {
        val rows = l.menuRows(count = 5, selected = 1)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.first })
        val keysTop = l.mainKeys.minOf { it.box.top }
        assertTrue("キーのボタンに重ならない", rows.all { it.second.bottom < keysTop })
        assertTrue("中央に収まる", rows.all { it.second.left >= l.center.left && it.second.right <= l.center.right })
        assertTrue("行どうしは重ならない", rows.zipWithNext().all { (a, b) -> a.second.bottom <= b.second.top })
        assertTrue("指で押せる高さ", rows.all { it.second.height >= 28f })

        // 画面がもっと多ければ、選んでいる行の前後だけを出す
        assertEquals(listOf(5, 6, 7, 8, 9), l.menuRows(count = 10, selected = 9).map { it.first })
        assertEquals(listOf(2, 3, 4, 5, 6), l.menuRows(count = 10, selected = 4).map { it.first })
        assertEquals(emptyList<Pair<Int, Box>>(), l.menuRows(count = 0, selected = 0))
    }
}

class SettingsTest {
    @Test
    fun validatesUrlAndToken() {
        val t = "test-token-0123456789"
        assertNull(Settings.validate("http://192.168.0.132:8790", t))
        assertNull(Settings.validate(" http://192.168.0.132:8790/ ", " $t\n"))
        assertTrue(Settings.validate("192.168.0.132:8790", t) != null)
        assertTrue(Settings.validate("http://", t) != null)
        assertTrue(Settings.validate("http://192.168.0.132:8790", "short") != null)
    }
}
