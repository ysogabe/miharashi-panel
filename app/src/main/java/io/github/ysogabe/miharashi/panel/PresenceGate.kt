package io.github.ysogabe.miharashi.panel

/** 利用者がそばにいるかの推定(設計書 §4.5)。 */
enum class Presence { PRESENT, ABSENT, UNKNOWN }

/**
 * 在席ゲートの判定。`reason` は診断表示用(`lux_unavailable` / `dark` / `bright` など)で、`lux` と `darkForMs` も診断用。
 * 通知の本文などは持たない。
 */
data class GateVerdict(
    val presence: Presence,
    val reason: String,
    /** 判定に使った照度。不明なら null */
    val lux: Float? = null,
    /** 暗闇が続いている時間(ms)。暗闇でない・不明なら null */
    val darkForMs: Long? = null,
)

/** 在席の判定。[AutoSwitchPolicy] はこれだけを知る(照度センサーや設定の実体は知らない。R10)。 */
fun interface PresenceGate {
    fun evaluate(nowMs: Long): GateVerdict
}

/** 最新の照度。センサーがない・値が未着・不正値のときは `lux = null`。 */
data class LuxSample(val lux: Float?)

/**
 * 照度ゲートの設定。暗闇は `lux <= darkLux` が `darkSeconds` 秒以上続いた状態。
 * `darkSeconds = 0` は「閾値以下になったら即 ABSENT」。
 */
data class GateConfig(val darkLux: Float, val darkSeconds: Int) {
    init {
        require(darkLux.isFinite() && darkLux >= 0f) { "darkLux" }
        require(darkSeconds >= 0) { "darkSeconds" }
    }

    val darkMs: Long get() = darkSeconds * 1000L

    companion object {
        fun from(s: DaidaiSettings) = GateConfig(s.gateDarkLux, s.gateDarkSeconds)
    }
}

/** 照度として使える値か(null・NaN・Infinity・負は使えない)。 */
internal fun validLux(lux: Float?): Float? = lux?.takeIf { it.isFinite() && it >= 0f }

/**
 * 暗闇(lux ≤ 閾値)の開始時刻を持ち、継続時間を返す(設計書 §4.5)。Android には依存しない。
 *
 * 既存の [DarknessPolicy] と違い、消灯を送ったあとも継続時間を返す。互いに状態を共有しない。
 * 開始時刻を捨てる条件: 照度が使えない値(null・NaN・Infinity・負)になったとき、[reset]、
 * 明るくなったとき、閾値または継続秒数の設定が変わったとき([configure])。
 * センサーが on-change 型でも、時刻を進めるだけで継続時間が求まる。
 */
class LuxDarkTracker(config: GateConfig) {
    private var config = config
    private var darkSince: Long? = null
    private var lastLux: Float? = null

    /** 照度の値が来るたびに呼ぶ(センサーのコールバック)。 */
    fun observe(lux: Float?, nowMs: Long) {
        val v = validLux(lux)
        lastLux = v
        if (v == null) {
            darkSince = null
            return
        }
        if (v <= config.darkLux) {
            if (darkSince == null) darkSince = nowMs
        } else {
            darkSince = null
        }
    }

    /**
     * 設定を渡す。閾値または継続秒数が前回と違えば、古い開始時刻を捨てる(古い開始時刻と新しい閾値を混ぜない)。
     * センサーは変化したときしかイベントを出さないので、最後に見た照度が新しい閾値でも暗ければ、
     * この時刻から数え直す(暗い部屋で設定だけ変えて、永久に「暗くなって間もない」になるのを避ける)。
     */
    fun configure(newConfig: GateConfig, nowMs: Long) {
        if (newConfig == config) return
        config = newConfig
        darkSince = null
        val v = lastLux ?: return
        if (v <= newConfig.darkLux) darkSince = nowMs
    }

    /** 暗闇が続いている時間(ms)。暗闇でない・照度不明なら null。 */
    fun darkDurationMs(nowMs: Long): Long? = darkSince?.let { (nowMs - it).coerceAtLeast(0L) }

    /** `onPause` 用。開始時刻と最後の照度を捨てる(背面にいた間を暗闇の継続に含めない。復帰後の最初の値までは不明)。 */
    fun reset() {
        darkSince = null
        lastLux = null
    }
}

/**
 * 照度による在席ゲート(設計書 §4.5)。
 *
 * - 照度が不明 → [Presence.UNKNOWN](`lux_unavailable`)
 * - 暗闇が `darkSeconds` 以上続いている → [Presence.ABSENT](`dark`)
 * - それ以外(明るい、または暗くなって間もない) → [Presence.PRESENT](`bright`)
 *
 * 結線: センサーの値は [observe] へ流す(`LightSensorReader` のコールバックに相乗り)。
 * `sample` は最新の照度(onResume 直後で未着なら null)、`config` は現在の設定(呼ぶたびに変更を検知する)。
 * [observe] を呼ばなければ暗闇の継続は数えられず、PRESENT 側に倒れる点に注意(結線は T5 で行う)。
 */
class AmbientLightGate(
    private val sample: () -> LuxSample,
    private val config: () -> GateConfig,
    private val tracker: LuxDarkTracker = LuxDarkTracker(config()),
) : PresenceGate {

    fun observe(lux: Float?, nowMs: Long) {
        tracker.configure(config(), nowMs)
        tracker.observe(lux, nowMs)
    }

    /** `onPause` 用。 */
    fun reset() = tracker.reset()

    override fun evaluate(nowMs: Long): GateVerdict {
        val cfg = config()
        tracker.configure(cfg, nowMs)
        val lux = validLux(sample().lux) ?: return GateVerdict(Presence.UNKNOWN, REASON_LUX_UNAVAILABLE)
        val darkFor = if (lux <= cfg.darkLux) tracker.darkDurationMs(nowMs) else null
        return if (darkFor != null && darkFor >= cfg.darkMs) {
            GateVerdict(Presence.ABSENT, REASON_DARK, lux, darkFor)
        } else {
            GateVerdict(Presence.PRESENT, REASON_BRIGHT, lux, darkFor)
        }
    }

    companion object {
        const val REASON_LUX_UNAVAILABLE = "lux_unavailable"
        const val REASON_DARK = "dark"
        const val REASON_BRIGHT = "bright"
    }
}
