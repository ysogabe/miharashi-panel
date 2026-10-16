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

    /** 巡回中の実機の `/status` の rotation を書き換える(無効・項目が欠けている・知らない state) */
    private fun withRotation(rotation: String) =
        fixture("status-rotation-on.json").replace("\"rotation\":{\"resume_in_s\":null,\"state\":\"on\"}", "\"rotation\":$rotation")

    @Test
    fun parsesRotationState() {
        assertNull("rotation を返さない古いキオスク", KioskStatus.parse(fixture("status-five-screens.json")).rotation)
        assertEquals(RotationInfo(RotationState.ON), KioskStatus.parse(fixture("status-rotation-on.json")).rotation)
        val paused = KioskStatus.parse(fixture("status-rotation-paused.json"))
        assertEquals(RotationInfo(RotationState.PAUSED, resumeInS = 298), paused.rotation)
        assertEquals("止めたキーで次の画面へ移っている", "room", paused.currentScreen?.id)
        assertNull("巡回が無効", KioskStatus.parse(withRotation("null")).rotation)
        assertEquals(
            "resume_in_s が無くても止まっていることは出す",
            RotationInfo(RotationState.PAUSED, resumeInS = null),
            KioskStatus.parse(withRotation("""{"state":"paused"}""")).rotation,
        )
        assertNull("知らない state", KioskStatus.parse(withRotation("""{"state":"warming"}""")).rotation)
    }

    /** パネルから止めたまま(miharashi #57)。キオスクが control を返さなければ止められない */
    @Test
    fun parsesRotationStopAndControl() {
        assertFalse("control を返さない古いキオスク", KioskStatus.parse(fixture("status-rotation-on.json")).rotation!!.control)
        assertEquals(
            RotationInfo(RotationState.ON, control = true),
            KioskStatus.parse(withRotation("""{"control":true,"resume_in_s":null,"state":"on"}""")).rotation,
        )
        // パネルの「止める」(POST /api/v1/rotation {"mode":"off"})の直後の実機の /status(2026-10-16)
        val stopped = KioskStatus.parse(fixture("status-rotation-off.json"))
        val off = stopped.rotation!!
        assertEquals(RotationInfo(RotationState.OFF, control = true), off)
        assertFalse("止めたままは一時停止ではない", off.paused)
        assertEquals("photo", stopped.currentScreen?.id)
        assertEquals(
            "止めたままなら残りの秒数は読まない",
            RotationInfo(RotationState.OFF, control = true),
            KioskStatus.parse(withRotation("""{"control":true,"resume_in_s":120,"state":"off"}""")).rotation,
        )
        val st = PanelState().withStatus(stopped, 10_000)
        assertNull("止めたままなら数え下げない", st.rotationResumeInS(10_000))
    }

    @Test
    fun resumeCountsDownFromReceipt() {
        val paused = KioskStatus.parse(fixture("status-rotation-paused.json"))
        val st = PanelState().withStatus(paused, nowMs = 10_000)
        assertEquals(298L, st.rotationResumeInS(10_000))
        assertEquals("切り上げ", 297L, st.rotationResumeInS(11_500))
        assertEquals(1L, st.rotationResumeInS(307_999))
        assertEquals("過ぎても 0 未満にしない", 0L, st.rotationResumeInS(320_000))
        val extended = st.withStatus(paused.copy(rotation = RotationInfo(RotationState.PAUSED, resumeInS = 300)), nowMs = 20_000)
        assertEquals("新しい状態が届いたら、そこから数え直す", 300L, extended.rotationResumeInS(20_000))

        val on = KioskStatus.parse(fixture("status-rotation-on.json"))
        assertNull("巡回中", PanelState().withStatus(on, 10_000).rotationResumeInS(10_000))
        assertNull("状態がまだ無い", PanelState().rotationResumeInS(10_000))
    }

    @Test
    fun parsesMenuState() {
        // パネルの 0 でメニューを開き、2 で 1 つ下げた実機の /status(表示中は AIRCRAFT、選んでいるのは T3 AGENTS)
        val open = KioskStatus.parse(fixture("status-menu-open.json"))
        assertEquals(4, open.menu)
        assertEquals("aircraft", open.currentScreen?.id)
        assertNull("閉じている", KioskStatus.parse(fixture("status-keys-aircraft.json")).menu)
        assertNull("menu を返さない古いキオスク", KioskStatus.parse(fixture("status-rotation-on.json")).menu)

        // 添字と id が食い違えば id を信じる。どちらも無ければ閉じているものとして扱う
        val menu = "\"menu\":{\"id\":\"agents\",\"index\":4}"
        val json = fixture("status-menu-open.json")
        assertEquals(1, KioskStatus.parse(json.replace(menu, "\"menu\":{\"id\":\"photo\",\"index\":4}")).menu)
        assertNull(KioskStatus.parse(json.replace(menu, "\"menu\":{\"id\":\"radar\",\"index\":9}")).menu)
    }

    @Test
    fun parsesKeyLabels() {
        assertEquals(
            mapOf("prev" to "上を選ぶ", "next" to "下を選ぶ", "select" to "移動", "up" to "上を選ぶ", "down" to "下を選ぶ", "menu" to "閉じる"),
            KioskStatus.parse(fixture("status-menu-open.json")).keys,
        )
        val aircraft = KioskStatus.parse(fixture("status-keys-aircraft.json")).keys!!
        assertEquals("最寄りの機体", aircraft["select"])
        assertEquals("前の機体", aircraft["up"])
        assertNull("keys を返さない古いキオスク", KioskStatus.parse(fixture("status-rotation-on.json")).keys)

        // 何もしないキーは null(室内環境と天気・T3 Agent の画面の 5・8・2)。項目が欠けていても null
        val none = fixture("status-keys-aircraft.json")
            .replace("\"down\":\"次の機体\"", "\"down\":null")
            .replace("\"select\":\"最寄りの機体\",", "")
        val keys = KioskStatus.parse(none).keys!!
        assertEquals(KEY_NAMES.toSet(), keys.keys)
        assertNull(keys["down"])
        assertNull(keys["select"])
        assertEquals("前の機体", keys["up"])
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
