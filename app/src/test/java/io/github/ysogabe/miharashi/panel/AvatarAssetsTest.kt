package io.github.ysogabe.miharashi.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
class AvatarAssetsTest {
    private val colors = AvatarColors(face = Color.rgb(10, 200, 250), feature = Color.rgb(5, 5, 5), mark = Color.rgb(255, 200, 0))
    private val assets get() = RuntimeEnvironment.getApplication().assets

    private fun draw(avatars: AvatarAssets, pose: String, size: Int = 160, c: AvatarColors = colors): IntArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        avatars.draw(Canvas(bitmap), pose, Box(0f, 0f, size.toFloat(), size.toFloat()), c)
        return IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size); bitmap.recycle() }
    }

    private fun solid(rgb: Int, size: Int = 40) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(rgb) }

    private val noFallback = AvatarPainter { _, _, _ -> fail("fallback must not be used") }

    // ---- 素材: 6 ポーズすべてに PNG と MP4 がある

    @Test fun everyPoseHasAPngAndAnMp4InAssets() {
        assertEquals(6, DaidaiPose.ALL.size)
        for (pose in DaidaiPose.ALL) {
            val png = assets.open(AvatarAssets.imagePath(pose)).use { BitmapFactory.decodeStream(it) }
            assertNotNull(pose, png)
            assertEquals(pose, 480, png.width)
            assertEquals(pose, 480, png.height)
            // MediaPlayer が AssetFileDescriptor で開ける(APK で無圧縮)
            assets.openFd(AvatarAssets.videoPath(pose)).use { assertTrue(pose, it.length > 10_000) }
        }
        assertEquals("daidai/daidai-milestone.mp4", AvatarAssets.videoPath("milestone"))
        assertEquals("daidai/daidai-idle.png", AvatarAssets.imagePath("dancing"))
    }

    // ---- 静止画(PNG)

    @Test fun pngPicturesAreDifferentPerPoseAndDrawnFromAssets() {
        val avatars = AvatarAssets.fromAssets(assets)
        val pictures = DaidaiPose.ALL.associateWith { draw(avatars, it) }
        val names = pictures.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size)
            assertFalse("${names[i]} vs ${names[j]}", pictures.getValue(names[i]).contentEquals(pictures.getValue(names[j])))
        // ドット絵の色は使わない
        for ((pose, px) in pictures) assertEquals(pose, 0, px.count { it == colors.face })
        // milestone / making は idle に読み替えない
        assertFalse(pictures.getValue("milestone").contentEquals(pictures.getValue("idle")))
        assertFalse(pictures.getValue("making").contentEquals(pictures.getValue("idle")))
    }

    @Test fun pictureKeepsTheCornersAndPaintsTheCenter() {
        val size = 200
        val px = draw(AvatarAssets.fromAssets(assets), "idle", size)
        // 四隅(角丸の外)は背景のまま
        for ((x, y) in listOf(0 to 0, 1 to 1, size - 1 to 0, size - 2 to 1, 0 to size - 1, 1 to size - 2, size - 1 to size - 1, size - 2 to size - 2))
            assertEquals("($x,$y)", Color.BLACK, px[y * size + x])
        // 辺の中ほどと中央は絵が描かれる(絵の背景は白く不透明)
        for ((x, y) in listOf(size / 2 to 0, 0 to size / 2, size - 1 to size / 2, size / 2 to size - 1, size / 2 to size / 2))
            assertNotEquals("($x,$y)", Color.BLACK, px[y * size + x])
        // 角の半径は短い辺の 14%(28px): 半径の内側の点は描かれ、外側の点は描かれない
        val r = AvatarFrame.cornerRadius(size.toFloat())
        assertEquals(28f, r, 0.01f)
        val inside = (r * (1 - Math.sqrt(0.5)) + 3).toInt() // 角の円弧の内側ぎりぎり
        assertNotEquals(Color.BLACK, px[inside * size + inside])
        val outside = (r * (1 - Math.sqrt(0.5)) - 3).toInt()
        assertEquals(Color.BLACK, px[outside * size + outside])
    }

    @Test fun drawsInsideTheGivenAreaForPicturesAndSprites() {
        for (avatars in listOf(AvatarAssets.fromAssets(assets), AvatarAssets())) {
            val size = 200
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            avatars.draw(Canvas(bitmap), "listening", Box(50f, 50f, 150f, 150f), colors)
            val px = IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
            for (y in 0 until size) for (x in 0 until size) {
                if (x < 49 || x > 150 || y < 49 || y > 150) assertEquals("($x,$y)", Color.BLACK, px[y * size + x])
            }
        }
    }

    @Test fun nonSquareAreaFitsTheShortSideAndCenters() {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        BitmapAvatarPainter({ solid(Color.WHITE) }, noFallback).paint(Canvas(bitmap), Box(0f, 0f, 300f, 200f), colors)
        val px = IntArray(300 * 200).also { bitmap.getPixels(it, 0, 300, 0, 0, 300, 200) }
        assertEquals(Color.WHITE, px[100 * 300 + 150]) // 中央
        assertEquals(Color.WHITE, px[100 * 300 + 60]) // 正方形(200×200)は x = 50〜250
        assertEquals(Color.BLACK, px[100 * 300 + 40])
        assertEquals(Color.BLACK, px[100 * 300 + 260])
    }

    @Test fun pictureIsLoadedOnceAndALoadFailureFallsBackToTheSprite() {
        var loads = 0
        val ok = BitmapAvatarPainter({ loads++; solid(Color.WHITE) }, noFallback)
        repeat(3) { draw(AvatarAssets(DaidaiPose.ALL.associateWith { ok }), "idle") }
        assertEquals(1, loads)

        for (broken in listOf<() -> Bitmap?>({ null }, { throw java.io.FileNotFoundException() })) {
            var tries = 0
            val sprite = PixelSprite(AvatarAssets.IDLE_ROWS)
            val painter = BitmapAvatarPainter({ tries++; broken() }, sprite)
            val avatars = AvatarAssets(DaidaiPose.ALL.associateWith { painter })
            assertArrayEquals(draw(AvatarAssets(DaidaiPose.ALL.associateWith { sprite }), "idle"), draw(avatars, "idle"))
            draw(avatars, "idle")
            assertEquals(1, tries) // 失敗も覚えて、毎フレーム読み直さない
        }
    }

    @Test fun anAssetManagerWithoutThePngFallsBackToTheSpritePerPose() {
        // 壊れた PNG と見立てて、読めない loader を持つ painter だけがドット絵になる
        val avatars = AvatarAssets(mapOf(
            "idle" to BitmapAvatarPainter({ null }, AvatarAssets.PIXEL_PAINTERS.getValue("idle")),
            "talking" to BitmapAvatarPainter({ solid(Color.WHITE) }, noFallback),
        ))
        assertTrue(draw(avatars, "idle").count { it == colors.face } > 1000)
        assertEquals(0, draw(avatars, "talking").count { it == colors.face })
    }

    // ---- 仮のドット絵(最後の砦)

    @Test fun spritesAreSixteenBySixteen() {
        for (rows in listOf(AvatarAssets.IDLE_ROWS, AvatarAssets.LISTENING_ROWS, AvatarAssets.WORKING_ROWS, AvatarAssets.TALKING_ROWS)) {
            assertEquals(16, rows.size)
            for (r in rows) {
                assertEquals(r, 16, r.length)
                assertTrue(r, r.all { it in ".FEA" })
            }
        }
    }

    @Test fun spritesCoverAllSixPosesAndMilestoneAndMakingUseTheIdleSprite() {
        val assets = AvatarAssets()
        val pictures = DaidaiPose.ALL.associateWith { draw(assets, it) }
        for ((pose, px) in pictures) assertTrue(pose, px.count { it == colors.face } > 1000)
        val four = listOf("idle", "listening", "working", "talking")
        for (i in four.indices) for (j in i + 1 until four.size)
            assertFalse("${four[i]} vs ${four[j]}", pictures.getValue(four[i]).contentEquals(pictures.getValue(four[j])))
        assertArrayEquals(pictures.getValue("idle"), pictures.getValue("milestone"))
        assertArrayEquals(pictures.getValue("idle"), pictures.getValue("making"))
        // 考え中の点・耳のマークは印の色で出る
        assertTrue(pictures.getValue("working").any { it == colors.mark })
        assertTrue(pictures.getValue("listening").any { it == colors.mark })
        assertFalse(pictures.getValue("idle").any { it == colors.mark })
    }

    @Test fun unknownPoseDrawsLikeIdle() {
        for (avatars in listOf(AvatarAssets(), AvatarAssets.fromAssets(assets))) {
            assertArrayEquals(draw(avatars, "idle"), draw(avatars, "nonsense"))
            assertArrayEquals(draw(avatars, "idle"), draw(avatars, ""))
        }
    }

    @Test fun painterCanBeReplaced() {
        var called = 0
        val custom = AvatarAssets(DaidaiPose.ALL.associateWith { AvatarPainter { _, _, _ -> called++ } })
        draw(custom, "talking")
        draw(custom, "unknown")
        assertEquals(2, called)
    }

    // ---- 照度の補正

    @Test fun strengthZeroIsTheIdentityAndLeavesTheColorsAlone() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
            AvatarContrast.matrixValues(0f), 0f)
        assertNull(AvatarContrast.filter(0f))
        assertNull(AvatarContrast.filter(Float.NaN))
        assertNull(AvatarContrast.filter(-1f))
        // 絵に 0 をかけても、PNG のピクセルがそのまま出る(1 対 1 の大きさ)
        val png = assets.open(AvatarAssets.imagePath("idle")).use { BitmapFactory.decodeStream(it) }
        val px = draw(AvatarAssets.fromAssets(assets), "idle", 480, colors.copy(contrast = 0f))
        for (y in 60..420 step 7) for (x in 60..420 step 5) assertEquals("($x,$y)", png.getPixel(x, y), px[y * 480 + x])
    }

    @Test fun strengthOneRaisesTheContrastAroundMidGray() {
        val v = AvatarContrast.matrixValues(1f)
        assertEquals(1.25f, v[0], 1e-6f)
        assertEquals(1.25f, v[6], 1e-6f)
        assertEquals(1.25f, v[12], 1e-6f)
        assertEquals(1f, v[18], 0f) // アルファは変えない
        // 中間灰(127.5)は動かない
        assertEquals(127.5f, 127.5f * v[0] + v[4], 1e-3f)
        assertTrue(AvatarContrast.matrixValues(0.5f)[0] in 1.05f..1.2f)
        assertEquals(AvatarContrast.matrixValues(1f)[0], AvatarContrast.matrixValues(7f)[0], 0f) // 範囲外は丸める

        fun center(rgb: Int, strength: Float): Int {
            val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            BitmapAvatarPainter({ solid(rgb) }, noFallback).paint(Canvas(bmp), Box(0f, 0f, 200f, 200f), colors.copy(contrast = strength))
            return bmp.getPixel(100, 100)
        }
        val dark = Color.rgb(100, 100, 100)
        val bright = Color.rgb(200, 200, 200)
        assertEquals(dark, center(dark, 0f))
        assertTrue(Color.red(center(dark, 1f)) in 92..94) // 1.25×100 − 31.875 = 93.1
        assertTrue(Color.red(center(bright, 1f)) in 217..219) // 1.25×200 − 31.875 = 218.1
        assertTrue(Color.red(center(Color.rgb(128, 128, 128), 1f)) in 127..129)
        assertEquals(Color.WHITE, center(Color.WHITE, 1f))
    }

    @Test fun theSameFilterIsUsedForPicturesAndTheVideoLayer() {
        // 静止画(BitmapAvatarPainter)と動画レイヤー(DaidaiView.videoLayerPaint)に、同じ補正が渡る
        val view = DaidaiView(RuntimeEnvironment.getApplication())
        assertNull(view.videoLayerPaint.colorFilter)
        view.contrastStrength = 1f
        assertNotNull(view.videoLayerPaint.colorFilter)
        val gray = Color.rgb(100, 100, 100)
        val viaLayerPaint = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(solid(gray, 10), 0f, 0f, Paint().apply { colorFilter = view.videoLayerPaint.colorFilter })
        }.getPixel(5, 5)
        val viaPicture = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
            BitmapAvatarPainter({ solid(gray) }, noFallback).paint(Canvas(it), Box(0f, 0f, 200f, 200f), colors.copy(contrast = view.contrastStrength))
        }.getPixel(100, 100)
        assertEquals(viaLayerPaint, viaPicture)
        assertNotEquals(gray, viaPicture)
        view.contrastStrength = 0f
        assertNull(view.videoLayerPaint.colorFilter)
    }
}
