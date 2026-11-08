package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class DaidaiTextTest {
    @Test fun stateLabelsFollowTheDesign() {
        assertEquals("待機中", DaidaiText.stateLabel(VoiceState.IDLE))
        assertEquals("聞き取り中", DaidaiText.stateLabel(VoiceState.LISTENING))
        assertEquals("聞き取り中", DaidaiText.stateLabel(VoiceState.HEARING))
        assertEquals("応答待ち", DaidaiText.stateLabel(VoiceState.WAITING))
        assertEquals("返答中", DaidaiText.stateLabel(VoiceState.REPLYING))
        assertEquals("エラー", DaidaiText.stateLabel(VoiceState.ERROR))
    }

    @Test fun everyStateHasAPoseInTheVocabulary() {
        for (s in VoiceState.entries) assertTrue(DaidaiText.pose(s, null, false) in DaidaiPose.ALL)
        assertEquals("idle", DaidaiText.pose(VoiceState.IDLE, null, false))
        assertEquals("listening", DaidaiText.pose(VoiceState.LISTENING, null, false))
        assertEquals("listening", DaidaiText.pose(VoiceState.HEARING, null, false))
        assertEquals("working", DaidaiText.pose(VoiceState.WAITING, null, false))
        assertEquals("talking", DaidaiText.pose(VoiceState.REPLYING, null, false))
        assertEquals("idle", DaidaiText.pose(VoiceState.ERROR, null, false))
    }

    @Test fun noticePoseWinsOnlyWhileTheCardIsShownAndIdle() {
        assertEquals("talking", DaidaiText.pose(VoiceState.IDLE, "talking", true))
        assertEquals("idle", DaidaiText.pose(VoiceState.IDLE, "talking", false))
        assertEquals("listening", DaidaiText.pose(VoiceState.LISTENING, "talking", true))
        assertEquals("idle", DaidaiText.pose(VoiceState.ERROR, "talking", true))
    }

    @Test fun unknownPoseNamesBecomeIdle() {
        assertEquals("idle", DaidaiPose.normalize(null))
        assertEquals("idle", DaidaiPose.normalize(""))
        assertEquals("idle", DaidaiPose.normalize("dancing"))
        assertEquals("idle", DaidaiPose.normalize("Talking"))
        assertEquals("idle", DaidaiText.pose(VoiceState.IDLE, "dancing", true))
        for (p in DaidaiPose.ALL) assertEquals(p, DaidaiPose.normalize(p))
    }

    @Test fun everyErrorCodeHasAFixedMessage() {
        val codes = listOf("not_configured", "mic_permission", "mic_unavailable", "mic_silent", "host_unreachable", "no_speech",
            "asr_failed", "timeout", "daidai_unavailable", "reply_audio_failed", "session_lost", "expired", "busy", "asr_unavailable")
        assertEquals(codes.toSet(), VoiceErrorCode.entries.map { it.code }.toSet())
        val messages = mutableSetOf<String>()
        for (code in codes) {
            val e = VoiceErrorCode.fromCode(code)
            assertNotNull(code, e)
            val m = DaidaiText.errorMessage(e)
            assertTrue(code, m.isNotBlank())
            assertNotEquals(code, DaidaiText.GENERIC_ERROR, m)
            messages += m
        }
        assertEquals("every code must have its own message", codes.size, messages.size)
    }

    @Test fun unknownOrMissingCodeGivesTheGenericMessage() {
        assertNull(VoiceErrorCode.fromCode("something_new"))
        assertNull(VoiceErrorCode.fromCode(null))
        assertEquals(DaidaiText.GENERIC_ERROR, DaidaiText.errorMessage(null))
    }

    @Test fun guidesExplainWhatToDo() {
        assertEquals("ブリッジが未設定です", DaidaiText.guideTitle(SetupGuide.NOT_CONFIGURED))
        assertTrue(DaidaiText.guideBody(SetupGuide.NOT_CONFIGURED).contains("設定"))
        assertTrue(DaidaiText.guideBody(SetupGuide.MIC_PERMISSION).contains("pm grant"))
        assertTrue(DaidaiText.guideBody(SetupGuide.MIC_PERMISSION).contains("android.permission.RECORD_AUDIO"))
        assertTrue(DaidaiText.guideBody(SetupGuide.MIC_PERMISSION).contains(DaidaiText.PACKAGE_NAME))
        assertEquals("", DaidaiText.guideBody(SetupGuide.NONE))
    }

    @Test fun uiStateDerivesPoseAndGuide() {
        val n = DaidaiNotice("a", "t", "b", 1)
        assertEquals("talking", DaidaiUiState(notice = n, noticePose = "talking").avatarPose)
        assertEquals("idle", DaidaiUiState(noticePose = "talking").avatarPose)
        assertEquals("working", DaidaiUiState(voice = VoiceState.WAITING).avatarPose)
        assertEquals(SetupGuide.NONE, DaidaiUiState().guide)
        assertEquals(SetupGuide.NOT_CONFIGURED, DaidaiUiState.forSettings(false).guide)
        assertEquals(SetupGuide.NONE, DaidaiUiState.forSettings(true).guide)
        assertEquals(SetupGuide.MIC_PERMISSION,
            DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.MIC_PERMISSION).guide)
        assertEquals(SetupGuide.NONE, DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.TIMEOUT).guide)
    }

    @Test fun configuredNeedsUrlAndToken() {
        assertFalse(DaidaiSettings().isConfigured())
        assertFalse(DaidaiSettings(url = "http://192.168.0.5:8765").isConfigured())
        assertFalse(DaidaiSettings(token = "x".repeat(32)).isConfigured())
        assertTrue(DaidaiSettings(url = "http://192.168.0.5:8765", token = "x".repeat(32)).isConfigured())
    }

    @Test fun toStringNeverContainsTheText() {
        val s = DaidaiUiState(
            notice = DaidaiNotice("a", "SECRET-TITLE", "SECRET-BODY", 1), transcript = "SECRET-HEARD",
            reply = "SECRET-REPLY", diagnostics = "SECRET-DIAG")
        val text = s.toString()
        for (w in listOf("SECRET-TITLE", "SECRET-BODY", "SECRET-HEARD", "SECRET-REPLY", "SECRET-DIAG")) assertFalse(w, text.contains(w))
    }

    @Test fun cleanRemovesControlAndBidiCharacters() {
        val raw = "a\u202Eb\u202Ac\u2066d\u2069e\u0007f\u200Bg\u0000h"
        assertEquals("abcdefgh", DaidaiText.clean(raw, 100, keepNewlines = false))
        assertEquals("a\nb", DaidaiText.clean("a\r\nb", 100, keepNewlines = true))
        assertEquals("ab", DaidaiText.clean("a\nb", 100, keepNewlines = false))
        assertEquals("", DaidaiText.clean(null, 100, keepNewlines = false))
        assertEquals(5, DaidaiText.clean("あ".repeat(50), 5, keepNewlines = false).length)
    }
}
