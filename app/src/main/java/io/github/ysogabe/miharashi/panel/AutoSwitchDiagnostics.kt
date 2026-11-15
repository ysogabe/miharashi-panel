package io.github.ysogabe.miharashi.panel

import java.util.Locale

/**
 * 直近の自動切替の判定(設計書 §4.5 末尾)。設定画面が 1 行で読む。プロセス内のメモリだけで、保存しない。
 * 「なぜ切り替わらなかったか」が分かる(実機検証 AC9)。照度と秒数だけを持ち、通知の中身は持たない。
 */
object AutoSwitchDiagnostics {
    const val NONE_TEXT = "まだ判定なし"

    @Volatile
    private var current: String? = null

    /** 設定画面に出す 1 行 */
    val line: String get() = current ?: NONE_TEXT

    /** 判定の結果を残す。UI スレッドから呼ぶ */
    fun record(result: AutoSwitchResult) {
        current = format(result)
    }

    /** テスト用。 */
    fun clear() {
        current = null
    }

    /** 例: `見送り: 暗闇 (0.4 lx, 52 秒)`、`切替: 明るい (180 lx)`、`見送り: 照度が取れない`。 */
    fun format(r: AutoSwitchResult): String = when (r.action) {
        AutoSwitchAction.SWITCH -> {
            val mic = r.micDenial?.let { "、マイクは開かない: ${denialText(it)}" } ?: ""
            "切替: ${r.verdict?.let(::gateText) ?: "条件を満たした"}$mic"
        }
        AutoSwitchAction.CARD_ONLY -> "更新のみ: すでにだいだい画面を表示中"
        AutoSwitchAction.DEFER -> "保留: 操作中(終わってから判定し直す)"
        AutoSwitchAction.SKIP -> "見送り: " + when (r.reason) {
            AutoSwitchReason.GATE_ABSENT -> r.verdict?.let(::gateText) ?: "不在とみなした"
            AutoSwitchReason.GATE_UNKNOWN -> "照度が取れない"
            AutoSwitchReason.DISABLED -> "自動切替が無効"
            AutoSwitchReason.LOW_PRIORITY -> "優先度が低い通知"
            AutoSwitchReason.TOO_OLD -> "古い通知"
            AutoSwitchReason.USER_BUSY -> "操作中"
            AutoSwitchReason.ON_DAIDAI_PAGE -> "すでにだいだい画面を表示中"
            AutoSwitchReason.PRESENT -> "条件を満たした"
        }
    }

    private fun gateText(v: GateVerdict): String {
        val label = when (v.reason) {
            AmbientLightGate.REASON_DARK -> "暗闇"
            AmbientLightGate.REASON_LUX_UNAVAILABLE -> return "照度が取れない"
            AmbientLightGate.REASON_BRIGHT -> if (v.darkForMs != null) "暗くなって間もない" else "明るい"
            else -> v.reason
        }
        val detail = buildList {
            v.lux?.let { add(luxText(it)) }
            v.darkForMs?.let { add("${it / 1000} 秒") }
        }
        return if (detail.isEmpty()) label else "$label (${detail.joinToString(", ")})"
    }

    private fun luxText(lux: Float) =
        if (lux >= 10f) String.format(Locale.ROOT, "%.0f lx", lux) else String.format(Locale.ROOT, "%.1f lx", lux)

    private fun denialText(d: MicDenial) = when (d) {
        MicDenial.AUTO_LISTEN_OFF -> "自動で聞く設定がオフ"
        MicDenial.AFTER_NO_SPEECH -> "直前の自動起動で無発話"
        MicDenial.COOLDOWN -> "クールダウン中"
        MicDenial.HOURLY_LIMIT -> "1 時間の上限"
    }
}
