package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** Offline only: no Activity, preferences, KioskSync, Client or network fixture is constructed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroVideoTest {
    private val screens = listOf(ScreenInfo("status", "状況"), ScreenInfo("photo", "写真"),
        ScreenInfo("room", "室内と天気"), ScreenInfo("agents", "エージェント"))

    private inner class Demo(context: Context) {
        var mono = 1_000L
        var epoch = Instant.parse("2026-01-01T03:59:35Z").toEpochMilli()
        val actions = mutableListOf<Action>()
        val sleeps = mutableListOf<Boolean>()
        var pending: Pair<Long, () -> Unit>? = null
        var photoInfo = true
        var status = KioskStatus(1, "DEMO", screens, 0, "OVERVIEW", null, true, 3600,
            DisplayInfo(true, "auto", true), RotationInfo(RotationState.ON, control = true))
        val view = PanelView(context, { action, done ->
            actions += action
            // Sending does not immediately change state: fake acknowledgement/status arrives 200ms later.
            check(pending == null)
            pending = mono + 200 to {
                status = when (action) {
                    is Action.Screen -> status.copy(current = screens.indexOfFirst { it.id == action.id }, menu = null)
                    is Action.Key -> when (action.name) {
                        "next" -> if (status.menu != null) status.copy(menu = (status.menu!! + 1) % screens.size)
                            else status.copy(current = ((status.current ?: 0) + 1) % screens.size)
                        "prev" -> if (status.menu != null) status.copy(menu = Math.floorMod(status.menu!! - 1, screens.size))
                            else status.copy(current = Math.floorMod((status.current ?: 0) - 1, screens.size))
                        "menu" -> status.copy(menu = if (status.menu == null) status.current else null)
                        "up" -> if (status.menu != null) status.copy(menu = Math.floorMod(status.menu!! - 1, screens.size))
                            else status.copy(photo = (Math.floorMod((status.photo?.first ?: 1) - 2, 12) + 1) to 12)
                        "down" -> if (status.menu != null) status.copy(menu = (status.menu!! + 1) % screens.size)
                            else status.copy(photo = ((status.photo?.first ?: 1) % 12 + 1) to 12)
                        "select" -> when {
                            status.menu != null -> status.copy(current = status.menu, menu = null)
                            status.current == 0 -> status.copy(page = if (status.page == "DETAILS") "OVERVIEW" else "DETAILS")
                            status.current == 1 -> { photoInfo = !photoInfo; status }
                            else -> status
                        }
                        else -> status
                    }
                    is Action.Display -> status.copy(display = DisplayInfo(action.mode != "force_off", action.mode, true))
                    is Action.Rotation -> status.copy(rotation = RotationInfo(
                        if (action.mode == "off") RotationState.OFF else RotationState.ON, control = true),
                        current = if (action.mode == "on") ((status.current ?: 0) + 1) % screens.size else status.current)
                    else -> error("Settings must never be used in the video")
                }
                if (action is Action.Key || action is Action.Screen) status = status.copy(
                    rotation = RotationInfo(RotationState.PAUSED, 90, true))
                publish()
                done(null)
            }
        }, { error("Settings must never be opened") }, { mono }, { epoch })

        init {
            view.layout(0, 0, 960, 480)
            view.configured = true
            view.wifi = true
            view.onSleepChanged = { sleeps += it }
            view.sleepTimeoutMs = 180_000
            publish()
        }

        fun publish() {
            val photo = status.current == 1
            val menu = status.menu != null
            status = status.copy(photo = if (photo) status.photo ?: (1 to 12) else null,
                page = if (status.current == 0) status.page ?: "OVERVIEW" else null,
                keys = mapOf("prev" to if (menu) "上を選ぶ" else "前の画面",
                    "next" to if (menu) "下を選ぶ" else "次の画面",
                    "select" to when {
                        menu -> "移動"
                        status.current == 0 -> if (status.page == "DETAILS") "概要" else "詳細"
                        photo -> if (photoInfo) "情報を隠す" else "情報を表示"
                        else -> null
                    },
                    "up" to if (menu) "上を選ぶ" else if (photo) "前の写真" else null,
                    "down" to if (menu) "下を選ぶ" else if (photo) "次の写真" else null,
                    "menu" to if (menu) "閉じる" else "メニュー"))
            view.state = PanelState(status, mono)
        }

        fun advance(ms: Long) {
            mono += ms
            epoch += ms
            pending?.takeIf { mono >= it.first }?.let { pending = null; it.second() }
        }

        fun touch(box: Box, down: Boolean) {
            MotionEvent.obtain(mono, mono, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                box.centerX, box.centerY, 0).let { view.onTouchEvent(it); it.recycle() }
        }
        fun tap(box: Box) { touch(box, true); touch(box, false) }
        fun bitmap(): Bitmap = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        fun key(id: String) = PanelLayout(960f, 480f).buttons.first { it.id == id }.box
        fun row(i: Int) = PanelLayout(960f, 480f).menuRows(screens.size, status.menu ?: 0).first { it.first == i }.second
    }

    @Test fun realTouchesWaitForStatusAndWakeSwallowsWholeGesture() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val d = Demo(RuntimeEnvironment.getApplication())
        d.tap(d.key("next"))
        assertEquals(0, d.view.state.status!!.current)
        d.advance(199)
        assertEquals(0, d.view.state.status!!.current)
        d.advance(1)
        assertEquals(1, d.view.state.status!!.current)
        assertEquals(1 to 12, d.status.photo)
        assertEquals("次の写真", d.status.keys!!["down"])
        assertEquals("情報を隠す", d.status.keys!!["select"])
        d.tap(d.key("down")); d.advance(200)
        assertEquals(2 to 12, d.status.photo)
        d.tap(d.key("up")); d.advance(200)
        assertEquals(1 to 12, d.status.photo)
        d.tap(d.key("menu")); d.advance(200)
        assertEquals("閉じる", d.status.keys!!["menu"])
        assertEquals("上を選ぶ", d.status.keys!!["prev"])
        assertEquals("下を選ぶ", d.status.keys!!["next"])
        d.tap(d.row(2)); d.advance(200)
        assertEquals(2, d.status.current); assertNull(d.status.menu)
        val card = PanelLayout(960f, 480f).screenCard
        d.tap(card); d.advance(200); assertEquals(RotationState.OFF, d.status.rotation!!.state)
        d.tap(card); d.advance(200); assertEquals(RotationState.ON, d.status.rotation!!.state)
        assertEquals(3, d.status.current)
        for (mode in listOf("off", "on", "auto")) {
            d.tap(d.key("display_$mode")); d.advance(200)
            assertEquals(mode != "off", d.status.display!!.on)
            assertEquals(if (mode == "auto") "auto" else "force_$mode", d.status.display!!.mode)
        }
        assertEquals("auto", d.status.display!!.mode)
        d.advance(180_000)
        val before = d.actions.size
        d.tap(d.key("next"))
        assertEquals(listOf(true, false), d.sleeps)
        assertEquals(before, d.actions.size)
        d.tap(d.key("next")); d.advance(200)
        assertEquals(before + 1, d.actions.size)
    }

    @Test fun nativeRenderingIsStableForFixedVirtualTimeline() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val first = Demo(RuntimeEnvironment.getApplication())
        val second = Demo(RuntimeEnvironment.getApplication())
        for (step in 0..20) {
            if (step == 2) { first.tap(first.key("next")); second.tap(second.key("next")) }
            if (step == 8) { first.tap(first.key("down")); second.tap(second.key("down")) }
            first.advance(100); second.advance(100)
            val a = first.bitmap(); val b = second.bitmap()
            assertTrue("Fixed clocks and public status must render identically at step $step", a.sameAs(b))
            assertNotEquals("Native drawing must produce visible pixels", a.getPixel(0, 0), a.getPixel(0, 60))
            a.recycle(); b.recycle()
        }
    }

    @Test fun virtualClockAndCountdownStayConsistent() {
        val d = Demo(RuntimeEnvironment.getApplication())
        d.tap(d.key("next")); d.advance(200)
        assertEquals(90L, d.view.state.rotationResumeInS(d.mono))
        d.advance(1333)
        assertEquals(89L, d.view.state.rotationResumeInS(d.mono))
        val clock = FlipClockModel()
        val start = Instant.parse("2026-01-01T03:59:59Z").toEpochMilli()
        clock.update(start, 9 * 3600_000, 0)
        assertTrue(clock.update(start + 1000, 9 * 3600_000, 1000))
        assertTrue(clock.isFlipping(1200))
        assertEquals("13:00", ClockMath.format(clock.minute))
        assertFalse(clock.isFlipping(1450))
    }

    /** Opt-in renderer. Every exported panel pixel comes from the production PanelView. */
    @Test fun renderFrames() {
        val output = System.getenv("PANEL_VIDEO_FRAMES") ?: return
        val versionName = requireNotNull(System.getenv("PANEL_VIDEO_VERSION"))
        val edit = JSONObject(File(System.getenv("PANEL_VIDEO_EDIT")).readText())
        TimeZone.setDefault(TimeZone.getTimeZone(edit.getString("timezone")))
        val version = edit.getJSONObject("versions").getJSONArray(versionName)
        val fps = edit.getInt("fps")
        val dir = File(output).apply { mkdirs() }
        val d = Demo(RuntimeEnvironment.getApplication())
        val layout = PanelLayout(960f, 480f)
        var observedFlip = false
        val clock = PanelView::class.java.getDeclaredField("flipClock").apply { isAccessible = true }.get(d.view) as FlipClockModel
        var frame = 0
        var elapsed = 0L
        // Align a real minute boundary 1.5s after the clock chapter starts, across both full versions.
        val clockAt = (0 until version.length()).takeWhile { version.getJSONArray(it).getString(0) != "clock" }
            .sumOf { version.getJSONArray(it).getDouble(1) }
        val start = Instant.parse("2026-01-01T04:00:00Z").toEpochMilli() - ((clockAt + 1.5) * 1000).toLong() - 180_000
        d.epoch = start
        for (sceneIndex in 0 until version.length()) {
            val scene = version.getJSONArray(sceneIndex)
            val name = scene.getString(0)
            val count = (scene.getDouble(1) * fps).toInt()
            if (name == "photo") { d.status = d.status.copy(current = 1, menu = null); d.publish() }
            if (name == "clock") {
                // Keep the default timeout: skip the wait in BOTH clocks, never shorten the product setting.
                d.advance(180_000)
                d.view.setForegroundActive(false) // no platform scheduling; updateSleep still checks real IdleTimer on touch
                // Trigger the same idle check used by the foreground ticker, without Activity/network setup.
                val checkIdle = PanelView::class.java.getDeclaredMethod("updateSleep").apply { isAccessible = true }
                checkIdle.invoke(d.view)
            }
            val taps = when (name) {
                "selection" -> listOf(0.10 to d.key("next"), 0.32 to d.key("prev"), 0.54 to d.key("menu"), 0.77 to d.row(1))
                "photo" -> listOf(0.16 to d.key("down"), 0.43 to d.key("down"), 0.72 to d.key("up"))
                "rotation" -> listOf(0.16 to layout.screenCard, 0.65 to layout.screenCard)
                "monitor" -> listOf(0.12 to d.key("display_off"), 0.40 to d.key("display_on"), 0.70 to d.key("display_auto"))
                "clock" -> listOf(0.78 to d.key("next"))
                else -> emptyList()
            }.map { (at, box) -> (at * count).toInt() to box }
            for (local in 0 until count) {
                val nextElapsed = frame * 1000L / fps
                d.advance(nextElapsed - elapsed); elapsed = nextElapsed
                // Refresh the public status each second, preserving the countdown deadline.
                if (frame % fps == 0 && d.pending == null) {
                    val left = d.view.state.rotationResumeInS(d.mono)
                    if (left != null) d.status = d.status.copy(rotation = d.status.rotation!!.copy(resumeInS = left))
                    d.publish()
                }
                taps.forEach { (at, box) ->
                    if (local == at) d.touch(box, true)
                    if (local == at + 3) d.touch(box, false)
                }
                val bitmap = d.bitmap()
                if (name == "clock" && clock.isFlipping(d.mono)) observedFlip = true
                // Subtle touch marker outside the production renderer.
                taps.lastOrNull { local in it.first..it.first + 9 }?.let { (_, box) ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(180, 114, 226, 197)
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
                    }
                    Canvas(bitmap).drawCircle(box.centerX, box.centerY, 20f, paint)
                }
                File(dir, "%05d.png".format(frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                frame++
            }
        }
        assertEquals(versionName.toInt() * fps, frame)
        if (versionName != "10") {
            assertTrue("Production PanelView must flip across the minute boundary", observedFlip)
            assertEquals(listOf(true, false), d.sleeps)
            assertEquals(12, d.actions.size) // 4 selection + 3 photo + 2 rotation + 3 monitor; wake sends none
        }
    }
}
