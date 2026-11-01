package io.github.ysogabe.miharashi.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientClockDrawingTest {
    @Test fun brightClockHasWhiteDigitsAndColonDespiteToneDrift() {
        var now = 0L
        val panel = PanelView(RuntimeEnvironment.getApplication(), { _, _ -> }, {},
            monotonicMs = { now }, epochMs = { 0L })
        panel.layout(0, 0, 1024, 600)
        panel.sleepTimeoutMs = 1L
        now = 30_000L
        PanelView::class.java.getDeclaredMethod("updateSleep").apply { isAccessible = true }.invoke(panel)
        fun pixels(strength: Float): IntArray {
            panel.contrastStrength = strength
            val bitmap = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(bitmap))
            return IntArray(1024 * 600).also {
                bitmap.getPixels(it, 0, 1024, 0, 0, 1024, 600)
                bitmap.recycle()
            }
        }
        val dim = pixels(0f)
        val bright = pixels(1f)
        assertFalse(dim.any { it == Color.WHITE })
        assertTrue(bright.count { it == Color.WHITE } > 100)
        // 中央のコロンも白く、背景は黒。数字だけを明るくする退行を防ぐ。
        val k = ClockLayout(1024f, 600f)
        val x = (k.originX + ClockDrift.dx(k, now) + k.colonX).toInt()
        val y = (k.originY + ClockDrift.dy(k, now) + k.cardH * 0.36f).toInt()
        assertEquals(Color.WHITE, bright[y * 1024 + x])
        assertEquals(Color.BLACK, bright[0])
    }
}
