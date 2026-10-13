package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fixtures/ は miharashi の実機の `/api/v1/status` をそのまま写したもの。 */
fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { name }.readText()

class PanelStateTest {
    @Test
    fun parsesPhotoScreen() {
        val s = KioskStatus.parse(fixture("status-photo.json"))
        assertEquals(1, s.schemaVersion)
        assertEquals("geoframe-n100", s.host)
        assertEquals(listOf("status", "photo"), s.screens.map { it.id })
        assertEquals(1, s.current)
        assertEquals("PHOTO FRAME", s.currentScreen?.title)
        assertNull("page は null", s.page)
        assertEquals(6 to 528, s.photo)
        assertTrue(s.feedOnline)
        assertEquals(180L, s.uptimeSeconds)
    }

    @Test
    fun parsesStatusPage() {
        val s = KioskStatus.parse(fixture("status-details.json"))
        assertEquals("status", s.currentScreen?.id)
        assertEquals("DETAILS", s.page)
    }

    @Test
    fun parsesStartingKioskWithoutScreen() {
        val s = KioskStatus.parse(fixture("status-starting.json"))
        assertNull(s.current)
        assertNull(s.currentScreen)
        assertNull(s.photo)
        assertFalse(s.feedOnline)
    }

    @Test
    fun outOfRangeIndexIsUnknown() {
        val json = fixture("status-photo.json").replace("\"index\":1", "\"index\":7")
        assertNull(KioskStatus.parse(json).current)
    }

    @Test(expected = org.json.JSONException::class)
    fun rejectsWrongShape() {
        KioskStatus.parse("""{"ok":true}""")
    }

    @Test
    fun freshnessFollowsLastSuccess() {
        val s = KioskStatus.parse(fixture("status-photo.json"))
        val st = PanelState().withStatus(s, nowMs = 1_000)
        assertEquals(Freshness.OFFLINE, PanelState().freshness(1_000))
        assertEquals(Freshness.FRESH, st.freshness(6_000))
        assertEquals(Freshness.STALE, st.freshness(6_001))
        assertEquals(Freshness.STALE, st.freshness(16_000))
        assertEquals(Freshness.OFFLINE, st.freshness(16_001))
    }

    @Test
    fun errorKeepsLastStatusAndSuccessClearsError() {
        val s = KioskStatus.parse(fixture("status-photo.json"))
        val st = PanelState().withStatus(s, 1_000).withError("接続できない")
        assertEquals(s, st.status)
        assertEquals("接続できない", st.lastError)
        assertNull(st.withStatus(s, 2_000).lastError)
    }
}
