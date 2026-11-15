package io.github.ysogabe.miharashi.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** 設定画面の「直近の自動切替の判定」の 1 行(設計書 §4.5、AC9 の診断)。 */
class AutoSwitchDiagnosticsTest {
    @Before fun clear() = AutoSwitchDiagnostics.clear()
    @After fun clearAfter() = AutoSwitchDiagnostics.clear()

    private fun fmt(a: AutoSwitchAction, r: AutoSwitchReason, v: GateVerdict? = null, mic: MicDenial? = null) =
        AutoSwitchDiagnostics.format(AutoSwitchResult(a, r, v, openMic = mic == null && a == AutoSwitchAction.SWITCH, micDenial = mic))

    @Test fun noJudgementYet() {
        assertEquals("まだ判定なし", AutoSwitchDiagnostics.line)
    }

    @Test fun gateOutcomes() {
        assertEquals("見送り: 暗闇 (0.4 lx, 52 秒)", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.GATE_ABSENT,
            GateVerdict(Presence.ABSENT, AmbientLightGate.REASON_DARK, 0.4f, 52_900)))
        assertEquals("切替: 明るい (180 lx)", fmt(AutoSwitchAction.SWITCH, AutoSwitchReason.PRESENT,
            GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT, 180f)))
        assertEquals("切替: 明るい (9.5 lx)", fmt(AutoSwitchAction.SWITCH, AutoSwitchReason.PRESENT,
            GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT, 9.5f)))
        assertEquals("切替: 暗くなって間もない (0.0 lx, 12 秒)", fmt(AutoSwitchAction.SWITCH, AutoSwitchReason.PRESENT,
            GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT, 0f, 12_000)))
        assertEquals("見送り: 照度が取れない", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.GATE_UNKNOWN,
            GateVerdict(Presence.UNKNOWN, AmbientLightGate.REASON_LUX_UNAVAILABLE)))
        assertEquals("見送り: 照度が取れない", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.GATE_UNKNOWN))
    }

    @Test fun nonGateOutcomes() {
        assertEquals("見送り: 自動切替が無効", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.DISABLED))
        assertEquals("見送り: 優先度が低い通知", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.LOW_PRIORITY))
        assertEquals("見送り: 古い通知", fmt(AutoSwitchAction.SKIP, AutoSwitchReason.TOO_OLD))
        assertEquals("更新のみ: すでにだいだい画面を表示中", fmt(AutoSwitchAction.CARD_ONLY, AutoSwitchReason.ON_DAIDAI_PAGE))
        assertEquals("保留: 操作中(終わってから判定し直す)", fmt(AutoSwitchAction.DEFER, AutoSwitchReason.USER_BUSY))
    }

    @Test fun micDenialIsAppendedToASwitch() {
        val v = GateVerdict(Presence.PRESENT, AmbientLightGate.REASON_BRIGHT, 180f)
        for (d in MicDenial.entries) {
            val s = fmt(AutoSwitchAction.SWITCH, AutoSwitchReason.PRESENT, v, d)
            assertEquals(true, s.startsWith("切替: 明るい (180 lx)、マイクは開かない: "))
        }
    }

    @Test fun recordKeepsOnlyTheLatestAndExposesNoNoticeText() {
        AutoSwitchDiagnostics.record(AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.DISABLED))
        AutoSwitchDiagnostics.record(AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.TOO_OLD))
        assertEquals("見送り: 古い通知", AutoSwitchDiagnostics.line)
        assertFalse(AutoSwitchDiagnostics.line.contains("DISABLED"))
    }
}
