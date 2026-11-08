package io.github.ysogabe.miharashi.panel

/**
 * 画面 B の配置(設計書 §5.6)。`PanelLayout` と同じく 960×480 を基準に、画面の大きさに合わせて拡大・縮小する。
 * 上にヘッダー(A と同じ矩形)、左にアバター、右に通知カード(あれば)・状態チップと認識結果・返答、
 * 下にページ点(`PagerLayout` が描く)のための余白。通知がないとき(`hasCard == false`)はカードを出さず、右の列を上へ詰める。
 */
class DaidaiLayout(val width: Float, val height: Float, val hasCard: Boolean) {
    val scale = minOf(width / PanelLayout.BASE_W, height / PanelLayout.BASE_H)
    private val ox = (width - PanelLayout.BASE_W * scale) / 2
    private val oy = (height - PanelLayout.BASE_H * scale) / 2

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        Box(ox + l * scale, oy + t * scale, ox + r * scale, oy + b * scale)

    /** 画面 A のヘッダーと同じ矩形(`PanelLayout.header`) */
    val header = PanelLayout(width, height).header

    /** ヘッダーの右端の「● マイク使用中」を置く場所 */
    val micBadge = box(700f, 8f, 940f, 48f)

    /** アバターの正方形 */
    val avatar = box(24f, 112f, 296f, 384f)

    /** 通知カード。通知がなければ null */
    val card: Box? = if (hasCard) box(RIGHT_L, 72f, RIGHT_R, 192f) else null

    private val statusTop = if (hasCard) 204f else 84f

    /** 状態チップ(「聞き取り中」など) */
    val chip = box(RIGHT_L, statusTop, RIGHT_L + 140f, statusTop + 40f)

    /** 認識結果(1 行) */
    val transcript = box(RIGHT_L + 156f, statusTop, RIGHT_R, statusTop + 40f)

    /** 返答・エラーの吹き出し・「話しかけてください」を置く場所 */
    val reply = box(RIGHT_L, statusTop + 56f, RIGHT_R, CONTENT_BOTTOM)

    /** 未設定・権限なしの案内。右の列全体 */
    val guide = box(RIGHT_L, 72f, RIGHT_R, CONTENT_BOTTOM)

    /** ページ点(`PagerLayout` が描く)のために空けておく帯。ここには何も置かない */
    val dotsArea = box(0f, CONTENT_BOTTOM + 12f, PanelLayout.BASE_W, PanelLayout.BASE_H)

    /** 基準の大きさ(960×480 での値)を、今の画面の大きさに直す */
    fun px(base: Float) = base * scale

    /** カードの中、返答の吹き出しの中の余白(基準値) */
    val padding get() = px(14f)

    companion object {
        private const val RIGHT_L = 328f
        private const val RIGHT_R = 936f
        private const val CONTENT_BOTTOM = 440f

        /** 返答の最大行数(長いときは縮小→省略で収める) */
        const val REPLY_MAX_LINES = 4
        const val LINE_HEIGHT = 1.3f

        /** 返答の下に併記する失敗理由(返答音声の失敗など)の文字の大きさ(基準値)と、返答との間隔 */
        const val REPLY_NOTE_SIZE = 18f
        const val REPLY_NOTE_GAP = 6f

        /** 返答の文字の大きさ(基準値、大きい順)。4 行が返答の領域に収まる大きさから縮める */
        val REPLY_SIZES = listOf(28f, 24f, 21f, 18f)
        val CARD_BODY_SIZES = listOf(20f, 18f, 16f)
        val GUIDE_SIZES = listOf(22f, 20f, 18f)
        const val CARD_BODY_MAX_LINES = 2
        const val GUIDE_MAX_LINES = 6
    }
}
