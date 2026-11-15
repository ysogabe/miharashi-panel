package io.github.ysogabe.miharashi.panel

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * アバターの絵・動画にかける、照度による明るさ補正(`contrastStrength`、0〜1)。
 * コントラストを上げるだけの ColorMatrix で、中間灰(127.5)を動かさない。
 * 静止画の `Paint` と動画レイヤー(`TextureView.setLayerPaint`)の両方に、同じ [filter] を使う。
 */
object AvatarContrast {
    /** 強さ 1 のときのコントラスト倍率(1.0 → 1.25) */
    const val MAX_GAIN = 1.25f

    private const val MID = 127.5f

    /** `strength` を 0〜1 に丸める(NaN は 0) */
    fun clamp(strength: Float): Float = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f

    /** 4×5 の行列(ColorMatrix の並び)。強さ 0 は恒等。アルファは変えない */
    fun matrixValues(strength: Float): FloatArray {
        val gain = 1f + (MAX_GAIN - 1f) * clamp(strength)
        val shift = MID * (1f - gain)
        return floatArrayOf(
            gain, 0f, 0f, 0f, shift,
            0f, gain, 0f, 0f, shift,
            0f, 0f, gain, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun matrix(strength: Float): ColorMatrix = ColorMatrix(matrixValues(strength))

    /** `Paint.colorFilter` にそのまま渡せる補正。強さ 0 は補正なしなので null */
    fun filter(strength: Float): ColorFilter? =
        if (clamp(strength) <= 0f) null else ColorMatrixColorFilter(matrix(strength))
}
