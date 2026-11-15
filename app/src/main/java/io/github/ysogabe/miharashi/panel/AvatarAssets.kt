package io.github.ysogabe.miharashi.panel

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * アバターを塗る 3 色(仮のドット絵用。顔・目や口・印)と、照度の補正の強さ(`contrast`、0〜1)。
 * 呼び出し側が照度の補正をかけた色を渡す。絵(PNG)は 3 色を使わず、`contrast` だけを [AvatarContrast] で効かせる。
 */
data class AvatarColors(val face: Int, val feature: Int, val mark: Int, val contrast: Float = 0f)

/** あるポーズの絵を、`area`(正方形を想定)に描く。 */
fun interface AvatarPainter {
    fun paint(canvas: Canvas, area: Box, colors: AvatarColors)
}

/** アバターの角丸カード枠。本体 miharashi の `AVATAR_CORNER_RATIO` と同じ(表示した絵の短い辺の 14%)。静止画も動画も同じ半径で切り抜く。 */
object AvatarFrame {
    const val CORNER_RATIO = 0.14f

    fun cornerRadius(side: Float): Float = side * CORNER_RATIO
}

/**
 * assets の PNG を、`area` に収まる大きさで中央に置き、角丸([AvatarFrame])で切り抜いて描く [AvatarPainter]。
 * 絵は 1 度だけ読む(最初に描くとき)。読めなかったとき(壊れている・無い)は、その後ずっと `fallback` を描く。
 * 照度の補正は `colors.contrast` を [AvatarContrast.filter] で絵にかける。
 */
class BitmapAvatarPainter(private val load: () -> Bitmap?, private val fallback: AvatarPainter) : AvatarPainter {
    private var loaded = false
    private var shader: BitmapShader? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()

    private fun ensureLoaded(): Bitmap? {
        if (!loaded) {
            loaded = true
            bitmap = try { load()?.takeIf { it.width > 0 && it.height > 0 } } catch (e: Exception) { null }
            shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        return bitmap
    }

    override fun paint(canvas: Canvas, area: Box, colors: AvatarColors) {
        val bmp = ensureLoaded()
        val sh = shader
        if (bmp == null || sh == null) {
            fallback.paint(canvas, area, colors)
            return
        }
        val scale = minOf(area.width / bmp.width, area.height / bmp.height)
        if (!(scale > 0f)) return
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = area.centerX - w / 2
        val top = area.centerY - h / 2
        matrix.setScale(scale, scale)
        matrix.postTranslate(left, top)
        sh.setLocalMatrix(matrix)
        paint.shader = sh
        paint.colorFilter = AvatarContrast.filter(colors.contrast)
        rect.set(left, top, left + w, top + h)
        val r = AvatarFrame.cornerRadius(minOf(w, h))
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/**
 * ドット絵のスプライト(16×16、文字の並び)を `Canvas` に四角で描く [AvatarPainter]。PNG が読めないときの最後の砦(仮の絵)。
 * `F` 顔、`E` 目や口、`A` 印(耳のマークや考え中の点)、`.` 透明。
 */
class PixelSprite(val rows: List<String>) : AvatarPainter {
    private val paint = Paint().apply { isAntiAlias = false }

    override fun paint(canvas: Canvas, area: Box, colors: AvatarColors) {
        val cell = minOf(area.width, area.height) / SIZE
        val left = area.centerX - cell * SIZE / 2
        val top = area.centerY - cell * SIZE / 2
        for ((y, row) in rows.withIndex()) {
            for ((x, ch) in row.withIndex()) {
                paint.color = when (ch) {
                    'F' -> colors.face
                    'E' -> colors.feature
                    'A' -> colors.mark
                    else -> continue
                }
                // 隣り合う四角のすき間が出ないよう、端を整数に丸める
                canvas.drawRect(
                    Math.round(left + x * cell).toFloat(), Math.round(top + y * cell).toFloat(),
                    Math.round(left + (x + 1) * cell).toFloat(), Math.round(top + (y + 1) * cell).toFloat(), paint)
            }
        }
    }

    companion object {
        const val SIZE = 16
    }
}

/**
 * ポーズ名 → 絵。[fromAssets] は assets の PNG([BitmapAvatarPainter])を描き、読めないポーズだけ仮のドット絵に落とす。
 * 引数なしの `AvatarAssets()` はドット絵だけ(AssetManager が無いテストなど)。語彙にない名前は `idle`。
 */
class AvatarAssets(private val painters: Map<String, AvatarPainter> = PIXEL_PAINTERS) {
    fun painter(pose: String): AvatarPainter =
        painters[DaidaiPose.normalize(pose)] ?: painters.getValue(DaidaiPose.IDLE)

    fun draw(canvas: Canvas, pose: String, area: Box, colors: AvatarColors) = painter(pose).paint(canvas, area, colors)

    companion object {
        /** assets 内のパス(ポーズごとに静止画 PNG と動画 MP4) */
        fun imagePath(pose: String) = "daidai/daidai-${DaidaiPose.normalize(pose)}.png"
        fun videoPath(pose: String) = "daidai/daidai-${DaidaiPose.normalize(pose)}.mp4"

        /** assets の PNG を描く [AvatarAssets]。PNG が読めないポーズは仮のドット絵(最後の砦) */
        fun fromAssets(assets: AssetManager): AvatarAssets = AvatarAssets(
            DaidaiPose.ALL.associateWith { pose ->
                BitmapAvatarPainter({ decode(assets, pose) }, PIXEL_PAINTERS.getValue(pose))
            })

        private fun decode(assets: AssetManager, pose: String): Bitmap? =
            assets.open(imagePath(pose)).use { BitmapFactory.decodeStream(it) }

        /** 待機: 目を開いた顔 */
        val IDLE_ROWS = listOf(
            "................",
            "................",
            "....FFFFFFFF....",
            "..FFFFFFFFFFFF..",
            ".FFFFFFFFFFFFFF.",
            ".FFFEEFFFFEEFFF.",
            ".FFFEEFFFFEEFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFEFFFFEFFFF.",
            ".FFFFFEEEEFFFFF.",
            ".FFFFFFFFFFFFFF.",
            "..FFFFFFFFFFFF..",
            "....FFFFFFFF....",
            "................",
            "................",
        )

        /** 聞き取り: 少し前のめり(右下へずれる)で、目が大きく、左に耳のマーク */
        val LISTENING_ROWS = listOf(
            "................",
            "................",
            "................",
            ".....FFFFFFFF...",
            "...FFFFFFFFFFFF.",
            "..FFFFFFFFFFFFFF",
            "..FFFEEFFFFEEFFF",
            "A.FFFEEFFFFEEFFF",
            ".AFFFEEFFFFEEFFF",
            ".AFFFFFFFFFFFFFF",
            "A.FFFFFFFFFFFFFF",
            "..FFFFFFEEFFFFFF",
            "..FFFFFFEEFFFFFF",
            "...FFFFFFFFFFFF.",
            ".....FFFFFFFF...",
            "................",
        )

        /** 考え中: 目を細め、口は一文字、頭の上に点々 */
        val WORKING_ROWS = listOf(
            "........A.A.A...",
            "................",
            "....FFFFFFFF....",
            "..FFFFFFFFFFFF..",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFEEEFFEEEFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFEEEEFFFFF.",
            ".FFFFFFFFFFFFFF.",
            "..FFFFFFFFFFFF..",
            "....FFFFFFFF....",
            "................",
            "................",
        )

        /** 返答中: 口が大きく開いている */
        val TALKING_ROWS = listOf(
            "................",
            "................",
            "....FFFFFFFF....",
            "..FFFFFFFFFFFF..",
            ".FFFFFFFFFFFFFF.",
            ".FFFEEFFFFEEFFF.",
            ".FFFEEFFFFEEFFF.",
            ".FFFFFFFFFFFFFF.",
            ".FFFFFEEEEFFFFF.",
            ".FFFFFEEEEFFFFF.",
            ".FFFFFEEEEFFFFF.",
            ".FFFFFFEEFFFFFF.",
            "..FFFFFFFFFFFF..",
            "....FFFFFFFF....",
            "................",
            "................",
        )

        /** 仮のドット絵。`milestone` / `making` は `idle` の絵を使う */
        val PIXEL_PAINTERS: Map<String, AvatarPainter> = mapOf(
            DaidaiPose.IDLE to PixelSprite(IDLE_ROWS),
            DaidaiPose.LISTENING to PixelSprite(LISTENING_ROWS),
            DaidaiPose.WORKING to PixelSprite(WORKING_ROWS),
            DaidaiPose.TALKING to PixelSprite(TALKING_ROWS),
            DaidaiPose.MILESTONE to PixelSprite(IDLE_ROWS),
            DaidaiPose.MAKING to PixelSprite(IDLE_ROWS),
        )
    }
}
