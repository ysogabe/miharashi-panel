package io.github.ysogabe.miharashi.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarAssetsTest {
    private val colors = AvatarColors(face = Color.rgb(10, 200, 250), feature = Color.rgb(5, 5, 5), mark = Color.rgb(255, 200, 0))

    private fun draw(assets: AvatarAssets, pose: String, size: Int = 160): IntArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        assets.draw(Canvas(bitmap), pose, Box(0f, 0f, size.toFloat(), size.toFloat()), colors)
        return IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size); bitmap.recycle() }
    }

    @Test fun spritesAreSixteenBySixteen() {
        for (rows in listOf(AvatarAssets.IDLE_ROWS, AvatarAssets.LISTENING_ROWS, AvatarAssets.WORKING_ROWS, AvatarAssets.TALKING_ROWS)) {
            assertEquals(16, rows.size)
            for (r in rows) {
                assertEquals(r, 16, r.length)
                assertTrue(r, r.all { it in ".FEA" })
            }
        }
    }

    @Test fun everyPoseIsDifferentAndDrawsSomething() {
        val assets = AvatarAssets()
        val pictures = DaidaiPose.ALL.associateWith { draw(assets, it) }
        for ((pose, px) in pictures) assertTrue(pose, px.count { it == colors.face } > 1000)
        val names = pictures.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size)
            assertFalse("${names[i]} vs ${names[j]}", pictures.getValue(names[i]).contentEquals(pictures.getValue(names[j])))
        // 考え中の点・耳のマークは印の色で出る
        assertTrue(pictures.getValue("working").any { it == colors.mark })
        assertTrue(pictures.getValue("listening").any { it == colors.mark })
        assertFalse(pictures.getValue("idle").any { it == colors.mark })
    }

    @Test fun unknownPoseDrawsLikeIdle() {
        val assets = AvatarAssets()
        assertArrayEquals(draw(assets, "idle"), draw(assets, "nonsense"))
        assertArrayEquals(draw(assets, "idle"), draw(assets, ""))
    }

    @Test fun painterCanBeReplaced() {
        var called = 0
        val custom = AvatarAssets(DaidaiPose.ALL.associateWith { AvatarPainter { _, _, _ -> called++ } })
        draw(custom, "talking")
        draw(custom, "unknown")
        assertEquals(2, called)
    }

    @Test fun drawsInsideTheGivenArea() {
        val size = 200
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        AvatarAssets().draw(Canvas(bitmap), "listening", Box(50f, 50f, 150f, 150f), colors)
        val px = IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
        for (y in 0 until size) for (x in 0 until size) {
            if (x < 49 || x > 150 || y < 49 || y > 150) assertEquals("($x,$y)", Color.BLACK, px[y * size + x])
        }
    }
}
