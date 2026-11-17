package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段階 2b-2(R15): 脳の区分の表記([DaidaiText.brainNotice])と、いつ出すか([DaidaiUiState.brainNotice])。 */
class DaidaiBrainNoticeTest {
    private fun brain(name: BrainName, egress: BrainEgress) = BrainInfo(name, egress)

    @Test fun textForEveryKindOfBrain() {
        assertEquals("だいだい(クラウド)に送ります", DaidaiText.brainNotice(brain(BrainName.DAIDAI, BrainEgress.CLOUD)))
        assertEquals("クラウドに送ります", DaidaiText.brainNotice(brain(BrainName.OTHER, BrainEgress.CLOUD)))
        assertEquals("LAN の別の機械に送ります", DaidaiText.brainNotice(brain(BrainName.OTHER, BrainEgress.LAN)))
        assertEquals("LAN の別の機械に送ります", DaidaiText.brainNotice(brain(BrainName.DAIDAI, BrainEgress.LAN)))
        assertEquals("外へ送られる場合があります", DaidaiText.brainNotice(brain(BrainName.OTHER, BrainEgress.UNKNOWN)))
        assertEquals("外へ送られる場合があります", DaidaiText.brainNotice(brain(BrainName.DAIDAI, BrainEgress.UNKNOWN)))
        assertEquals("このホストで答えます(外へは送りません)", DaidaiText.brainNotice(brain(BrainName.OTHER, BrainEgress.HOST)))
        assertEquals("このホストで答えます(外へは送りません)", DaidaiText.brainNotice(brain(BrainName.DAIDAI, BrainEgress.HOST)))
    }

    @Test fun anOldHostWithoutABrainShowsNothing() {
        assertNull(DaidaiText.brainNotice(null))
        for (s in VoiceState.entries) assertNull(DaidaiUiState(voice = s, micActive = true, cuePlaying = true).brainNotice)
    }

    @Test fun everyNonHostBrainHasANoticeThatNeverClaimsToStayOnTheHost() {
        for (n in BrainName.entries) for (e in BrainEgress.entries) {
            val t = DaidaiText.brainNotice(brain(n, e))!!
            if (e == BrainEgress.HOST) assertTrue(t.contains("外へは送りません")) else assertFalse("$n/$e: $t", t.contains("送りません"))
        }
    }

    @Test fun shownWhileTheMicIsOpenAndWhileACueIsPlaying() {
        val cloud = brain(BrainName.DAIDAI, BrainEgress.CLOUD)
        val expected = "だいだい(クラウド)に送ります"
        assertEquals(expected, DaidaiUiState(voice = VoiceState.LISTENING, micActive = true, brain = cloud).brainNotice)
        assertEquals(expected, DaidaiUiState(voice = VoiceState.HEARING, micActive = true, brain = cloud).brainNotice)
        assertEquals("合図の再生中(マイクはまだ閉じている)", expected, DaidaiUiState(voice = VoiceState.IDLE, cuePlaying = true, brain = cloud).brainNotice)
        assertEquals("マイクが録音中なら状態に関わらず", expected, DaidaiUiState(voice = VoiceState.WAITING, micActive = true, brain = cloud).brainNotice)
    }

    @Test fun shownWhilePreparingAfterTheCreateResponse() {
        val cloud = brain(BrainName.DAIDAI, BrainEgress.CLOUD)
        assertEquals("だいだい(クラウド)に送ります", DaidaiUiState(voice = VoiceState.IDLE, preparing = true, brain = cloud).brainNotice)
        assertNull("準備中でも脳が無い(作成の応答の前・旧ホスト)なら出さない", DaidaiUiState(voice = VoiceState.IDLE, preparing = true, brain = null).brainNotice)
        assertNull("準備中でなく、マイクも閉じている待機中は出さない", DaidaiUiState(voice = VoiceState.IDLE, preparing = false, brain = cloud).brainNotice)
        assertNull("応答待ち(WAITING)は出さない", DaidaiUiState(voice = VoiceState.WAITING, preparing = false, brain = cloud).brainNotice)
    }

    @Test fun notShownWhileTheMicIsClosed() {
        val cloud = brain(BrainName.DAIDAI, BrainEgress.CLOUD)
        for (s in listOf(VoiceState.IDLE, VoiceState.WAITING, VoiceState.REPLYING, VoiceState.ERROR)) {
            assertNull("$s", DaidaiUiState(voice = s, micActive = false, cuePlaying = false, brain = cloud).brainNotice)
        }
    }

    @Test fun theUiStateToStringKeepsTheBrainToItsClassOnly() {
        assertTrue(DaidaiUiState(brain = BrainInfo(BrainName.DAIDAI, BrainEgress.CLOUD)).toString().contains("brain=BrainInfo(name=DAIDAI, egress=CLOUD)"))
    }
}
