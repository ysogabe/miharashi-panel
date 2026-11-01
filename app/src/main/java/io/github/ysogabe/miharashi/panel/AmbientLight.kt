package io.github.ysogabe.miharashi.panel

import kotlin.math.ln
import kotlin.math.max

/** 照度と待機時計からウィンドウの明るさを一箇所で決める。-1 はシステム設定。 */
object AmbientBrightness {
    fun value(lux: Float?, enabled: Boolean, sleeping: Boolean): Float {
        if (!enabled || lux == null || !lux.isFinite() || lux < 0) return if (sleeping) 0.05f else -1f
        return (0.05 + 0.95 * ln(1.0 + lux) / ln(501.0)).toFloat().coerceIn(0.05f, 1f)
    }
}

/** 10 lx 以下は従来色、250 lx 以上は最大。滑らかな補間で境界の色飛びを避ける。 */
object AmbientContrast {
    fun strength(lux: Float?, enabled: Boolean): Float {
        if (!enabled || lux == null || !lux.isFinite() || lux < 0) return 0f
        val t = ((lux - 10f) / 240f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** on-change 型センサーでも、時刻を進めるだけで暗闇を判定できる。Android には依存しない。 */
class DarknessPolicy(val thresholdLux: Float) {
    init { require(thresholdLux.isFinite() && thresholdLux in 0f..100f) }

    val rearmLux = max(5f, thresholdLux + 4f)
    private var darkSince: Long? = null
    private var handled = false

    fun observe(lux: Float?, nowMs: Long) {
        if (lux == null || !lux.isFinite() || lux < 0) {
            darkSince = null
            return
        }
        if (lux >= rearmLux) handled = false
        if (lux <= thresholdLux) {
            if (!handled && darkSince == null) darkSince = nowMs
        } else {
            // 継続中に閾値を超えたら最初から数える。実行後の再判定には rearmLux が必要。
            darkSince = null
        }
    }

    fun remainingMs(nowMs: Long): Long? =
        if (handled) null else darkSince?.let { (DELAY_MS - (nowMs - it)).coerceAtLeast(0L) }

    /** 手動操作・自動送信の両方で、この暗闇中の追加送信を止める。 */
    fun handled() { handled = true; darkSince = null }

    /** 背面の時間を「暗闇の継続」に含めず、手動操作の優先は前面復帰後も維持。 */
    fun stopObserving() { darkSince = null }

    fun takeRequest(state: PanelState, nowMs: Long): Boolean {
        if (remainingMs(nowMs) != 0L) return false
        if (!canTurnOff(state, nowMs)) return false
        handled()
        return true
    }

    fun canTurnOff(state: PanelState, nowMs: Long): Boolean {
        val display = state.status?.display ?: return false
        return state.lastError == null && state.freshness(nowMs) == Freshness.FRESH &&
            display.control && display.mode == "auto" && display.on
    }

    companion object { const val DELAY_MS = 30_000L }
}
