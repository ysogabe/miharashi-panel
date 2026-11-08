package io.github.ysogabe.miharashi.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 画面 B(だいだい連携画面)の仮の画面。960×480 基準で「だいだい」とだけ描く(T4 で本物にする)。
 * ここでマイクは開かない。色は操作盤(A)の配色トークンと、照度による補正(`contrastStrength`)に従う。
 */
@SuppressLint("ViewConstructor")
class DaidaiView(context: Context) : View(context) {
    /** 照度による色補正。`PanelView.contrastStrength` と同じ値を受け取る */
    var contrastStrength = 0f
        set(value) {
            val next = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            if (field == next) return
            field = next
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun contrastColor(base: Int, target: Int): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * contrastStrength).toInt()
        return Color.rgb(channel(Color.red(base), Color.red(target)),
            channel(Color.green(base), Color.green(target)), channel(Color.blue(base), Color.blue(target)))
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(contrastColor(PanelView.BASE_BG, Color.BLACK))
        val scale = minOf(width / PanelLayout.BASE_W, height / PanelLayout.BASE_H)
        paint.textSize = 56f * scale
        paint.color = contrastColor(PanelView.BASE_FG, Color.WHITE)
        c.drawText("だいだい", width / 2f, height / 2f + 20f * scale, paint)
    }

    /** 触れた指は自分が受ける(ページの横ドラッグは `PagerLayout` が途中で奪う) */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = true
}
