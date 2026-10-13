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

/** ボタンを押したときにすること。 */
sealed interface Action {
    /** `/api/v1/input` にキーを送る */
    data class Key(val name: String) : Action

    /** `/api/v1/display` でキオスクのモニターを切り替える(auto / force_on / force_off) */
    data class Display(val mode: String) : Action

    /** 設定画面を開く */
    data object Settings : Action
}

/** 押せる場所。`enabled` が false のものは灰色で、押しても送らない。 */
data class Button(val id: String, val digit: String, val label: String, val action: Action, val box: Box, val enabled: Boolean)

/**
 * 操作盤の配置(設計書 §5.4)。960×480 を基準に、画面の大きさに合わせて拡大・縮小する。
 * 左のレールにモニターの 消灯 / 点灯 / 自動 と設定、中央に現在の画面と 4 / 5 / 6、右に 8 / 2 / 0、上にヘッダー。
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

    val settings = Button("settings", "", "設定", Action.Settings, box(8f, 420f, 192f, 472f), enabled = true)

    /** 4 / 5 / 6。P2 で使えるのはこの 3 つ(キオスクの prev / select / next) */
    val mainKeys = listOf(
        Button("prev", "4", "前へ", Action.Key("prev"), box(224f, 268f, 376f, 456f), enabled = true),
        Button("select", "5", "決定", Action.Key("select"), box(384f, 268f, 536f, 456f), enabled = true),
        Button("next", "6", "次へ", Action.Key("next"), box(544f, 268f, 696f, 456f), enabled = true),
    )

    /** 8 / 2 / 0。キオスクの PoC-003(#5)で統一操作が入るまで灰色 */
    val sideKeys = listOf(
        Button("up", "8", "項目 ↑", Action.Key("up"), box(736f, 72f, 944f, 192f), enabled = false),
        Button("down", "2", "項目 ↓", Action.Key("down"), box(736f, 204f, 944f, 324f), enabled = false),
        Button("menu", "0", "メニュー", Action.Key("menu"), box(736f, 336f, 944f, 456f), enabled = false),
    )

    /** キオスクのモニターの状態(左のレールの上)。その下に 消灯 / 点灯 / 自動、いちばん下に設定 */
    val monitorStatus = box(8f, 64f, 192f, 140f)

    /**
     * 4 / 5 / 6 から離して左のレールに縦に並べる(押し間違えないように)。
     * 押せるかどうかは、キオスクの `display.control` で決まる(PanelView が判断する)
     */
    val displayKeys = listOf(
        Button("display_off", "", "消灯", Action.Display("force_off"), box(8f, 148f, 192f, 224f), enabled = true),
        Button("display_on", "", "点灯", Action.Display("force_on"), box(8f, 232f, 192f, 308f), enabled = true),
        Button("display_auto", "", "自動", Action.Display("auto"), box(8f, 316f, 192f, 392f), enabled = true),
    )

    val buttons = mainKeys + sideKeys + displayKeys + settings

    fun hit(x: Float, y: Float): Button? = buttons.firstOrNull { it.box.contains(x, y) }

    companion object {
        const val BASE_W = 960f
        const val BASE_H = 480f
    }
}
