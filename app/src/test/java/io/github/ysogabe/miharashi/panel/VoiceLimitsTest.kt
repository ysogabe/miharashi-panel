package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class VoiceLimitsTest {
    @Test
    fun absoluteLimitsMatchDesignSection58() {
        assertEquals(45_000L, VoiceLimits.MIC_MAX_MS)
        assertEquals(120_000L, VoiceLimits.SESSION_MAX_MS)
        assertEquals(64_000, VoiceLimits.QUEUE_MAX_BYTES)
        assertEquals(640, VoiceLimits.CHUNK_MIN_BYTES)
        assertEquals(16_000, VoiceLimits.CHUNK_MAX_BYTES)
        assertEquals(2_000, VoiceLimits.REPLY_TEXT_MAX_CHARS)
        assertEquals(10 * 1024 * 1024, VoiceLimits.REPLY_AUDIO_MAX_BYTES)
        assertEquals(120_000L, VoiceLimits.REPLY_AUDIO_MAX_MS)
        assertEquals(3, VoiceLimits.SSE_MAX_RECONNECTS)
        // §5.2.6 の Panel 側の期限
        assertEquals(5_000L, VoiceLimits.CREATE_TIMEOUT_MS)
        assertEquals(4_000L, VoiceLimits.CHUNK_TIMEOUT_MS)
        assertEquals(3_000L, VoiceLimits.MIC_SILENT_WINDOW_MS)
        assertEquals(3_000L, VoiceLimits.NO_SPEECH_MARGIN_MS)
        assertEquals(5_000L, VoiceLimits.REPLY_TIMEOUT_MARGIN_MS)
        assertEquals(20_000L, VoiceLimits.REPLY_AUDIO_FETCH_TIMEOUT_MS)
        assertEquals(5_000L, VoiceLimits.PLAYBACK_MARGIN_MS)
        assertEquals(8_000L, VoiceLimits.ERROR_DISPLAY_MS)
        assertEquals(32_000, VoiceLimits.BYTES_PER_SECOND)
    }

    @Test
    fun hostLimitsAreClampedToPanelLimits() {
        val huge = EffectiveLimits.resolve(HostLimits(1_000_000, 1_000_000, Long.MAX_VALUE, 1_000_000, Long.MAX_VALUE))
        assertEquals(VoiceLimits.NO_SPEECH_MAX_S, huge.noSpeechS)
        assertEquals(VoiceLimits.MAX_UTTERANCE_MAX_S, huge.maxUtteranceS)
        assertEquals(VoiceLimits.REPLY_TIMEOUT_MAX_S, huge.replyTimeoutS)
        assertEquals(VoiceLimits.IDLE_EXPIRE_MAX_S, huge.idleExpireS)
        assertEquals(VoiceLimits.RETAIN_MAX_S, huge.retainS)
        // 応答待ちの最大(+5 秒)と録音の最大が、総時間の中に収まる設計であること
        assertTrue(VoiceLimits.REPLY_TIMEOUT_MAX_S * 1000 + VoiceLimits.REPLY_TIMEOUT_MARGIN_MS < VoiceLimits.SESSION_MAX_MS)
        assertTrue(VoiceLimits.MAX_UTTERANCE_MAX_S * 1000 < VoiceLimits.MIC_MAX_MS)
    }

    @Test
    fun missingOrInvalidValuesUseDefaults() {
        assertEquals(EffectiveLimits.DEFAULTS, EffectiveLimits.resolve(null))
        assertEquals(EffectiveLimits.DEFAULTS, EffectiveLimits.resolve(HostLimits()))
        assertEquals(EffectiveLimits.DEFAULTS, EffectiveLimits.resolve(HostLimits(0, -5, 0, -1, Long.MIN_VALUE)))
        val small = EffectiveLimits.resolve(HostLimits(noSpeechS = 4, replyTimeoutS = 20))
        assertEquals(4L, small.noSpeechS)
        assertEquals(20L, small.replyTimeoutS)
        assertEquals(VoiceLimits.MAX_UTTERANCE_DEFAULT_S, small.maxUtteranceS)
    }

    @Test
    fun hostErrorCodeMapping() {
        val ok = mapOf(
            "no_speech" to VoiceErrorCode.NO_SPEECH, "asr_failed" to VoiceErrorCode.ASR_FAILED, "timeout" to VoiceErrorCode.TIMEOUT,
            "daidai_unavailable" to VoiceErrorCode.DAIDAI_UNAVAILABLE, "expired" to VoiceErrorCode.EXPIRED, "busy" to VoiceErrorCode.BUSY,
            "asr_unavailable" to VoiceErrorCode.ASR_UNAVAILABLE, "session_lost" to VoiceErrorCode.SESSION_LOST,
            "reply_audio_failed" to VoiceErrorCode.REPLY_AUDIO_FAILED, "tts_failed" to VoiceErrorCode.REPLY_AUDIO_FAILED,
        )
        ok.forEach { (k, v) -> assertEquals(k, v, VoiceFailures.fromHostCode(k)) }
        // Panel 自身が決めるコードと未知のコードは採用しない
        for (k in listOf("not_configured", "mic_permission", "mic_unavailable", "mic_silent", "host_unreachable", "x", "", "NO_SPEECH", null)) {
            assertNull(k, VoiceFailures.fromHostCode(k))
        }
        assertEquals("unknown", VoiceFailures.reasonFromRaw(null))
        assertEquals("unknown", VoiceFailures.reasonFromRaw("日本語"))
        assertEquals("abc_1", VoiceFailures.reasonFromRaw("A-b c_1"))
        assertEquals(32, VoiceFailures.reasonFromRaw("a".repeat(100)).length)
    }

    @Test
    fun exceptionMapping() {
        fun code(e: Throwable) = VoiceFailures.fromException(e)
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, code(IOException("x")))
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, code(BridgeException(BridgeFailure.NETWORK)))
        assertEquals(VoiceErrorCode.HOST_UNREACHABLE, code(BridgeException(BridgeFailure.CLIENT_ERROR, 400, "bad_request")))
        assertEquals(VoiceErrorCode.BUSY, code(BridgeException(BridgeFailure.CLIENT_ERROR, 429, "busy")))
        assertEquals(VoiceErrorCode.ASR_UNAVAILABLE, code(BridgeException(BridgeFailure.ASR_UNAVAILABLE, 503)))
        assertEquals(VoiceErrorCode.EXPIRED, code(BridgeException(BridgeFailure.EXPIRED, 410)))
        assertEquals(VoiceErrorCode.SESSION_LOST, code(BridgeException(BridgeFailure.NOT_FOUND, 404)))
        assertTrue(VoiceFailures.isRetryable(IOException()))
        assertTrue(VoiceFailures.isRetryable(BridgeException(BridgeFailure.SERVER_ERROR, 504)))
        assertFalse(VoiceFailures.isRetryable(BridgeException(BridgeFailure.SERVER_ERROR, 500)))
        assertFalse(VoiceFailures.isRetryable(BridgeException(BridgeFailure.CLIENT_ERROR, 400)))
        assertFalse(VoiceFailures.isRetryable(BridgeException(BridgeFailure.EXPIRED, 410)))
    }

    @Test
    fun executorWorkerDeliversOnTheScheduler() {
        val sim = Sim()
        val worker = ExecutorSessionWorker(Executor { it.run() }, sim)
        val got = mutableListOf<Result<Int>>()
        worker.run({ 5 }) { got += it }
        worker.run({ throw IllegalStateException("x") }) { got += it }
        assertTrue("セッションのスレッドに戻すまで配達しない", got.isEmpty())
        sim.settle()
        assertEquals(5, got[0].getOrNull())
        assertTrue(got[1].isFailure)
    }

    @Test
    fun executorWorkerReportsRejection() {
        val sim = Sim()
        val worker = ExecutorSessionWorker(Executor { throw java.util.concurrent.RejectedExecutionException("full") }, sim)
        var got: Result<Int>? = null
        worker.run({ 1 }) { got = it }
        sim.settle()
        assertTrue(got!!.isFailure)
    }
}
