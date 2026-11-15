package io.github.ysogabe.miharashi.panel

import android.graphics.Canvas
import android.graphics.Paint

/** アバターを塗る 3 色。顔・目や口・印(耳のマークや考え中の点)。呼び出し側が照度の補正をかけた色を渡す。 */
data class AvatarColors(val face: Int, val feature: Int, val mark: Int)

/** あるポーズの絵を、`area`(正方形を想定)に描く。本物のアバターに差し替えるときは、これを実装する。 */
fun interface AvatarPainter {
    fun paint(canvas: Canvas, area: Box, colors: AvatarColors)
}

/**
 * ドット絵のスプライト(16×16、文字の並び)を `Canvas` に四角で描く [AvatarPainter]。画像ファイルを使わない。
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
 * ポーズ名 → 絵。今は Canvas だけで描く仮のドット絵(本物のアバター原画は利用者の用意待ち。設計書 O-6)。
 * 後で `painters` を差し替えれば(`PixelSprite` の代わりにビットマップを描く [AvatarPainter] など)、他は変えずに済む。
 * 語彙にない名前は `idle`。
 */
class AvatarAssets(private val painters: Map<String, AvatarPainter> = DEFAULT_PAINTERS) {
    fun painter(pose: String): AvatarPainter =
        painters[DaidaiPose.normalize(pose)] ?: painters.getValue(DaidaiPose.IDLE)

    fun draw(canvas: Canvas, pose: String, area: Box, colors: AvatarColors) = painter(pose).paint(canvas, area, colors)

    companion object {
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

        val DEFAULT_PAINTERS: Map<String, AvatarPainter> = mapOf(
            DaidaiPose.IDLE to PixelSprite(IDLE_ROWS),
            DaidaiPose.LISTENING to PixelSprite(LISTENING_ROWS),
            DaidaiPose.WORKING to PixelSprite(WORKING_ROWS),
            DaidaiPose.TALKING to PixelSprite(TALKING_ROWS),
        )
    }
}
