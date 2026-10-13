package io.github.ysogabe.miharashi.panel

/** 画面上の矩形。android.graphics.RectF に依存しないので JVM の単体テストで使える。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun inset(d: Float) = Box(left + d, top + d, right - d, bottom - d)
}

/** 押せる場所。`key` は `/api/v1/input` に送る名前。`enabled` が false のものは灰色で、押しても送らない。 */
data class Button(val id: String, val digit: String, val label: String, val key: String?, val box: Box, val enabled: Boolean)

/**
 * 操作盤の配置(設計書 §5.4)。960×480 を基準に、画面の大きさに合わせて拡大・縮小する。
 * 左に画面一覧のレール、中央に現在の画面と 4 / 5 / 6、右に 8 / 2 / 0、上にヘッダー。
 */
class PanelLayout(val width: Float, val height: Float) {
    val scale = minOf(width / BASE_W, height / BASE_H)
    private val ox = (width - BASE_W * scale) / 2
    private val oy = (height - BASE_H * scale) / 2

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        Box(ox + l * scale, oy + t * scale, ox + r * scale, oy + b * scale)

    val header = box(0f, 0f, BASE_W, 56f)
    val rail = box(0f, 56f, 200f, BASE_H)
    val center = box(200f, 56f, 720f, BASE_H)
    val side = box(720f, 56f, BASE_W, BASE_H)

    /** レールの画面一覧の i 行目 */
    fun railRow(i: Int) = box(8f, 96f + i * 52f, 192f, 96f + i * 52f + 46f)

    val settings = Button("settings", "", "設定", null, box(8f, 420f, 192f, 472f), enabled = true)

    /** 4 / 5 / 6。P2 で使えるのはこの 3 つ(キオスクの prev / select / next) */
    val mainKeys = listOf(
        Button("prev", "4", "前へ", "prev", box(224f, 268f, 376f, 456f), enabled = true),
        Button("select", "5", "決定", "select", box(384f, 268f, 536f, 456f), enabled = true),
        Button("next", "6", "次へ", "next", box(544f, 268f, 696f, 456f), enabled = true),
    )

    /** 8 / 2 / 0。キオスクの PoC-003(#5)で統一操作が入るまで灰色 */
    val sideKeys = listOf(
        Button("up", "8", "項目 ↑", "up", box(736f, 72f, 944f, 192f), enabled = false),
        Button("down", "2", "項目 ↓", "down", box(736f, 204f, 944f, 324f), enabled = false),
        Button("menu", "0", "メニュー", "menu", box(736f, 336f, 944f, 456f), enabled = false),
    )

    val buttons = mainKeys + sideKeys + settings

    fun hit(x: Float, y: Float): Button? = buttons.firstOrNull { it.box.contains(x, y) }

    companion object {
        const val BASE_W = 960f
        const val BASE_H = 480f
    }
}
