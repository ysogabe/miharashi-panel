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
    fun parsesMonitorState() {
        val d = KioskStatus.parse(fixture("status-display.json")).display
        assertEquals(DisplayInfo(on = true, mode = "force_on", control = true), d)
        val off = fixture("status-display.json")
            .replace("\"mode\":\"force_on\",\"on\":true", "\"mode\":\"force_off\",\"on\":false")
        assertEquals(DisplayInfo(on = false, mode = "force_off", control = true), KioskStatus.parse(off).display)
        assertNull("display を返さない古いキオスク", KioskStatus.parse(fixture("status-photo.json")).display)
        val noHa = fixture("status-photo.json")
            .replace("\"feed\"", "\"display\":{\"on\":true,\"mode\":null,\"control\":false},\"feed\"")
        assertEquals(DisplayInfo(on = true, mode = null, control = false), KioskStatus.parse(noHa).display)
    }

    @Test
    fun parsesFiveScreens() {
        val s = KioskStatus.parse(fixture("status-five-screens.json"))
        assertEquals(listOf("status", "photo", "room", "aircraft", "agents"), s.screens.map { it.id })
        assertEquals("AIRCRAFT", s.currentScreen?.title)
        assertEquals(51 to 527, s.photo)
    }

    /** `rotation` を返す前のキオスク(実機)を写したものに、miharashi #47 の形の rotation を足す */
    private fun withRotation(rotation: String) =
        fixture("status-five-screens.json").replace("\"schema_version\"", "\"rotation\":$rotation,\"schema_version\"")

    @Test
    fun parsesRotationState() {
        assertNull("rotation を返さない古いキオスク", KioskStatus.parse(fixture("status-five-screens.json")).rotation)
        assertNull("巡回が無効", KioskStatus.parse(withRotation("null")).rotation)
        assertEquals(
            RotationInfo(paused = false, resumeInS = null),
            KioskStatus.parse(withRotation("""{"state":"on","resume_in_s":null}""")).rotation,
        )
        assertEquals(
            RotationInfo(paused = true, resumeInS = 240),
            KioskStatus.parse(withRotation("""{"state":"paused","resume_in_s":240}""")).rotation,
        )
        assertEquals(
            "resume_in_s が無くても止まっていることは出す",
            RotationInfo(paused = true, resumeInS = null),
            KioskStatus.parse(withRotation("""{"state":"paused"}""")).rotation,
        )
        assertNull("知らない state", KioskStatus.parse(withRotation("""{"state":"warming"}""")).rotation)
    }

    @Test
    fun resumeCountsDownFromReceipt() {
        val paused = KioskStatus.parse(withRotation("""{"state":"paused","resume_in_s":240}"""))
        val st = PanelState().withStatus(paused, nowMs = 10_000)
        assertEquals(240L, st.rotationResumeInS(10_000))
        assertEquals("切り上げ", 239L, st.rotationResumeInS(11_500))
        assertEquals(1L, st.rotationResumeInS(249_999))
        assertEquals("過ぎても 0 未満にしない", 0L, st.rotationResumeInS(260_000))
        val extended = st.withStatus(paused.copy(rotation = RotationInfo(paused = true, resumeInS = 300)), nowMs = 20_000)
        assertEquals("新しい状態が届いたら、そこから数え直す", 300L, extended.rotationResumeInS(20_000))

        val on = KioskStatus.parse(withRotation("""{"state":"on","resume_in_s":null}"""))
        assertNull("巡回中", PanelState().withStatus(on, 10_000).rotationResumeInS(10_000))
        assertNull("状態がまだ無い", PanelState().rotationResumeInS(10_000))
    }

    @Test
    fun formatsMinSec() {
        assertEquals("4:12", formatMinSec(252))
        assertEquals("0:05", formatMinSec(5))
        assertEquals("10:00", formatMinSec(600))
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
