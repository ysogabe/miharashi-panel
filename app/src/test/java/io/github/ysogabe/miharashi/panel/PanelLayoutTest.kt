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
        assertEquals(listOf("4", "5", "6"), l.mainKeys.map { it.digit })
        assertEquals(listOf("prev", "select", "next").map(Action::Key), l.mainKeys.map { it.action })
        assertTrue(l.mainKeys.all { it.enabled })
    }

    @Test
    fun sideKeysAreGreyUntilPoc003() {
        assertEquals(listOf("8", "2", "0"), l.sideKeys.map { it.digit })
        assertTrue(l.sideKeys.none { it.enabled })
    }

    @Test
    fun monitorKeysSwitchTheKioskDisplay() {
        assertEquals(listOf("消灯", "点灯", "自動"), l.displayKeys.map { it.label })
        assertEquals(listOf("force_off", "force_on", "auto").map(Action::Display), l.displayKeys.map { it.action })
        // 4 / 5 / 6 より上、状態の文字(左)とは重ならない
        assertTrue(l.displayKeys.all { it.box.bottom < l.mainKeys[0].box.top && it.box.left > l.monitorRow.left + 150 })
        assertTrue(l.displayKeys.all { it.box.height >= 56 })
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
