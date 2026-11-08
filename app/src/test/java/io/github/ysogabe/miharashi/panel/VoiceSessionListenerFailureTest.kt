package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * リスナーが例外を投げたときの扱い。録音中の必須の通知(`onMicOpened`・マイク使用中を含むスナップショット)の失敗では録音を続けず
 * `mic_unavailable` で終わる。終了処理中の通知の失敗は握りつぶして、後ろの停止(通信の中断・DELETE・再生停止・onEnded)を進める。
 */
class VoiceSessionListenerFailureTest {
    /** マイクが止まり、通信が切られ、DELETE が送られ、以後は何も送られない */
    private fun assertStoppedWithMicUnavailable(r: Rig) {
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.last.error)
        assertEquals(VoiceState.ERROR, r.last.state)
        assertFalse("マイクが残っていない", r.audio.recording)
        assertFalse(r.last.micActive)
        assertTrue(r.audio.stopCount >= 1)
        assertTrue(r.bridge.aborts >= 1)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertEquals(listOf(false to "mic_unavailable"), r.listener.ended)
        // 通常の停止の順序: マイク停止 → 通信中断 → DELETE → 再生停止
        val stop = r.log.indexOf("audio.stop")
        val abort = r.log.indexOf("abort")
        val cancel = r.log.indexOf("cancel:s-1")
        val player = r.log.indexOf("player.stop")
        assertTrue(r.log.toString(), stop in 0 until abort && abort < cancel && cancel < player)
        // 例外のメッセージは診断にも画面にも出ない
        assertFalse(r.last.diagnostics.orEmpty().contains("secret"))
        assertFalse(r.last.diagnostics.orEmpty().contains("failed:"))
        // 以後は PCM を渡しても送らない・録音も始まらない
        val sent = r.bridge.chunks.size
        r.audio.feed(500)
        r.talk(2_000)
        assertEquals(sent, r.bridge.chunks.size)
        assertEquals(1, r.audio.startCount)
        assertFalse(r.audio.recording)
    }

    @Test
    fun onMicOpenedThrowing_endsTheSession() {
        val r = Rig()
        r.listener.throwOnMicOpened = true
        r.start()
        assertStoppedWithMicUnavailable(r)
        assertEquals("開始音の失敗で通知は 1 回", 1, r.listener.micOpenedAt.size)
    }

    @Test
    fun micActiveSnapshotThrowingWhenOpening_endsTheSession() {
        val r = Rig()
        r.listener.throwOnSnapshot = { it.micActive }
        r.start()
        assertStoppedWithMicUnavailable(r)
        assertTrue("マイク表示が出せないなら開始音も鳴らさない", r.listener.micOpenedAt.isEmpty())
    }

    @Test
    fun micActiveSnapshotThrowingWhileRecording_endsTheSession() {
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        assertEquals(VoicePhase.LISTENING, r.phase)
        r.listener.throwOnSnapshot = { it.micActive && it.state == VoiceState.HEARING }
        r.speech() // HEARING の表示が更新できない
        assertStoppedWithMicUnavailable(r)
    }

    @Test
    fun micActiveTranscriptSnapshotThrowingWhileRecording_endsTheSession() {
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        r.listener.throwOnSnapshot = { it.micActive }
        r.transcript("途中の認識")
        assertStoppedWithMicUnavailable(r)
    }

    @Test
    fun snapshotThrowingDuringTerminationIsSwallowedAndTheStopContinues() {
        // ERROR: 終了時のスナップショット(マイクは止まっている)が全部投げても、後片付けと onEnded は走る
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        r.listener.throwOnSnapshot = { true }
        r.hostError("asr_failed")
        assertEquals(VoicePhase.ERROR, r.phase)
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertTrue(r.bridge.aborts >= 1)
        assertEquals(listOf(false to "asr_failed"), r.listener.ended)
        assertTrue(r.player.stopCount >= 1)
        // 最後に試みたスナップショットは ERROR で、マイクは使用中ではない
        assertEquals(VoiceState.ERROR, r.last.state)
        assertFalse(r.last.micActive)
    }

    @Test
    fun snapshotThrowingOnCancelIsSwallowed() {
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        r.listener.throwOnSnapshot = { true }
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertFalse(r.audio.recording)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertEquals(listOf(false to "cancelled"), r.listener.ended)
        assertEquals(VoiceState.IDLE, r.last.state)
    }

    @Test
    fun snapshotThrowingAfterTheMicStoppedDoesNotEndTheSessionButDoneStillCompletes() {
        // 応答待ち・返答中のスナップショット(マイクなし)が投げても、セッションは続き、DONE まで進んで後片付けをする
        val r = Rig()
        r.startAndListen()
        r.talk(1_000)
        r.listener.throwOnSnapshot = { !it.micActive }
        r.waiting()
        assertEquals(VoicePhase.WAITING, r.phase)
        r.reply("返答", audio = null)
        assertEquals(VoicePhase.DONE, r.phase)
        assertNull(r.last.error)
        assertEquals("返答", r.last.reply)
        assertEquals(listOf(false to "done"), r.listener.ended)
        assertEquals(listOf("s-1"), r.bridge.cancels)
    }

    @Test
    fun onEndedThrowingIsSwallowed() {
        val r = Rig()
        r.startAndListen()
        r.listener.throwOnEnded = true
        r.hostError("timeout")
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertFalse(r.audio.recording)
    }

    @Test
    fun audioStopThrowingStillRunsTheRestOfTheStop() {
        // FakeAudio.throwOnStop は録音状態を false にしてから投げる。保証するのは「例外のあとも通信の中断・DELETE・再生停止が走る」まで
        val r = Rig()
        r.audio.throwOnStop = true
        r.listener.throwOnMicOpened = true
        r.start()
        assertEquals(VoicePhase.ERROR, r.phase)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, r.last.error)
        assertTrue(r.bridge.aborts >= 1)
        assertEquals(listOf("s-1"), r.bridge.cancels)
        assertTrue(r.player.stopCount >= 1)
        assertEquals(listOf(false to "mic_unavailable"), r.listener.ended)
    }
}
