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

    /** `/api/v1/screen` でその画面へ移る(メニューの行をタップしたとき) */
    data class Screen(val id: String) : Action

    /** `/api/v1/rotation` でキオスクの自動巡回を止めたままにする(off)・再開する(on)。巡回の行をタップしたとき */
    data class Rotation(val mode: String) : Action

    /** 設定画面を開く */
    data object Settings : Action
}

/** キーのボタンのアイコン。フォントの記号は端末によって欠けるので、PanelView が図形で描く。 */
enum class Icon { PREV, NEXT, SELECT, UP, DOWN, MENU }

/** 押せる場所。`icon` が null なら文字だけ。`enabled` が false のものは灰色で、押しても送らない。 */
data class Button(val id: String, val icon: Icon?, val label: String, val action: Action, val box: Box, val enabled: Boolean)

/**
 * 操作盤の配置(設計書 §5.4)。960×480 を基準に、画面の大きさに合わせて拡大・縮小する。
 * 左のレールにモニターの 消灯 / 点灯 / 自動 と設定、中央に現在の画面と 前・決定・次、右に 上・下・メニュー、上にヘッダー。
 * キーの数字(テンキーの 4 / 5 / 6 / 8 / 2 / 0)は出さず、アイコンとラベルで意味を出す。
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

    val settings = Button("settings", null, "設定", Action.Settings, box(8f, 420f, 192f, 472f), enabled = true)

    /** 前の画面・決定・次の画面(キオスクの prev / select / next、テンキーの 4 / 5 / 6) */
    val mainKeys = listOf(
        Button("prev", Icon.PREV, "前の画面", Action.Key("prev"), box(224f, 268f, 376f, 456f), enabled = true),
        Button("select", Icon.SELECT, "決定", Action.Key("select"), box(384f, 268f, 536f, 456f), enabled = true),
        Button("next", Icon.NEXT, "次の画面", Action.Key("next"), box(544f, 268f, 696f, 456f), enabled = true),
    )

    /**
     * 上・下・メニュー(キオスクの up / down / menu、テンキーの 8 / 2 / 0、miharashi #40)。
     * 上・下は画面の中の操作(写真の前後など)、メニューは画面一覧。`label` は汎用のもので、
     * キオスクが `/status` の `keys` で今の意味を返せば、PanelView がそちらを出す(miharashi #51)
     */
    val sideKeys = listOf(
        Button("up", Icon.UP, "前の項目", Action.Key("up"), box(736f, 72f, 944f, 192f), enabled = true),
        Button("down", Icon.DOWN, "次の項目", Action.Key("down"), box(736f, 204f, 944f, 324f), enabled = true),
        Button("menu", Icon.MENU, "メニュー", Action.Key("menu"), box(736f, 336f, 944f, 456f), enabled = true),
    )

    /** キオスクのモニターの状態(左のレールの上)。その下に 消灯 / 点灯 / 自動、いちばん下に設定 */
    val monitorStatus = box(8f, 64f, 192f, 140f)

    /**
     * キーのボタンから離して左のレールに縦に並べる(押し間違えないように)。
     * 押せるかどうかは、キオスクの `display.control` で決まる(PanelView が判断する)
     */
    val displayKeys = listOf(
        Button("display_off", null, "消灯", Action.Display("force_off"), box(8f, 148f, 192f, 224f), enabled = true),
        Button("display_on", null, "点灯", Action.Display("force_on"), box(8f, 232f, 192f, 308f), enabled = true),
        Button("display_auto", null, "自動", Action.Display("auto"), box(8f, 316f, 192f, 392f), enabled = true),
    )

    val buttons = mainKeys + sideKeys + displayKeys + settings

    fun hit(x: Float, y: Float): Button? = buttons.firstOrNull { it.box.contains(x, y) }

    /**
     * 現在の画面のカード(中央の、前の画面・決定・次の画面の上)。上に画面の名前、下の帯にキオスクの自動巡回の状態。
     * キオスクが巡回を止められるなら、カード全体が巡回を固定する・再開するボタンになる(細い行だと押しにくい)。
     * 押せるかどうかは巡回の状態で変わるので、`buttons` には入れず PanelView が判定する。
     * メニューを開いているあいだは出さない(一覧の行と重なる)
     */
    val screenCard = box(216f, 64f, 704f, 252f)

    /** カードの下の帯。左に巡回の状態、右に押すと何が起きるか */
    val rotationBand = box(216f, 196f, 704f, 252f)

    /** キオスクのメニューが開いているあいだ、画面の一覧を出す場所(中央の、キーのボタンより上) */
    val menuArea = box(216f, 64f, 704f, 236f)

    /**
     * メニューの行。`count` 個の画面のうち、選んでいる `selected` が見えるように最大 [MENU_ROWS] 行を出す。
     * 戻り値は (画面の添字, 行の矩形)。行をタップすると、その画面へ移る
     */
    fun menuRows(count: Int, selected: Int): List<Pair<Int, Box>> {
        val rows = minOf(count, MENU_ROWS)
        if (rows <= 0) return emptyList()
        val first = (selected - rows / 2).coerceIn(0, count - rows)
        val h = menuArea.height / MENU_ROWS
        val gap = 4f * scale
        return (0 until rows).map { r ->
            val top = menuArea.top + r * h
            (first + r) to Box(menuArea.left, top, menuArea.right, top + h - gap)
        }
    }

    companion object {
        const val BASE_W = 960f
        const val BASE_H = 480f

        /** メニューに一度に出す行の数。画面がもっと多ければ、選んでいる行の前後だけを出す */
        const val MENU_ROWS = 5
    }
}
