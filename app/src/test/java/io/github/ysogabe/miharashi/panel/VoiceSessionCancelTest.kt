package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** どの状態から `cancel()` してもマイクが止まり release される(AC4)。停止の順序も確かめる。マイクの `stop()` が例外を投げても後ろの手順は走る。 */
@RunWith(Parameterized::class)
class VoiceSessionCancelTest(private val where: String, private val throwOnStop: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} throwOnStop={1}")
        fun params(): List<Array<Any>> {
            val states = listOf("IDLE", "PREPARING", "LISTENING", "HEARING", "WAITING", "REPLYING_FETCH", "REPLYING_PLAY", "DONE", "ERROR")
            return states.flatMap { s -> listOf(arrayOf<Any>(s, false), arrayOf<Any>(s, true)) }
        }
    }

    private fun driveTo(r: Rig, where: String) {
        when (where) {
            "IDLE" -> Unit
            "PREPARING" -> {
                r.bridge.createReply = { hang() }
                r.start()
                assertEquals(VoicePhase.PREPARING, r.phase)
            }
            "LISTENING" -> {
                r.startAndListen()
                r.talk(500)
            }
            "HEARING" -> {
                r.startAndListen()
                r.talk(500)
                r.speech()
                assertEquals(VoicePhase.HEARING, r.phase)
            }
            "WAITING" -> {
                r.startAndListen()
                r.talk(500)
                r.waiting()
                assertEquals(VoicePhase.WAITING, r.phase)
            }
            "REPLYING_FETCH" -> {
                r.bridge.fetchReply = { hang() }
                r.startAndListen()
                r.reply()
                assertEquals(VoicePhase.REPLYING, r.phase)
                assertEquals(0, r.player.plays)
            }
            "REPLYING_PLAY" -> {
                r.startAndListen()
                r.reply()
                assertEquals(VoicePhase.REPLYING, r.phase)
                assertTrue(r.player.playing)
            }
            "DONE" -> {
                r.startAndListen()
                r.reply(audio = null)
                assertEquals(VoicePhase.DONE, r.phase)
            }
            "ERROR" -> {
                r.startAndListen()
                r.hostError("asr_failed")
                assertEquals(VoicePhase.ERROR, r.phase)
            }
            else -> error(where)
        }
    }

    @Test
    fun cancelStopsEverythingFromThisState() {
        val r = Rig()
        r.audio.throwOnStop = throwOnStop
        driveTo(r, where)
        val wasActive = r.phase.isActive
        val hadSession = r.phase != VoicePhase.IDLE && where != "PREPARING"
        val cancelsBefore = r.bridge.cancels.size
        val stopsBefore = r.audio.stopCount
        val endedBefore = r.listener.ended.size
        val mark = r.log.size
        r.cancel()

        assertEquals(VoicePhase.IDLE, r.phase)
        assertFalse("マイクが止まっている", r.audio.recording)
        assertTrue("マイクの stop(release)を呼んだ", r.audio.stopCount > stopsBefore)
        assertFalse(r.last.micActive)
        assertEquals(VoiceState.IDLE, r.last.state)
        assertEquals("認識結果・返答を残さない", VoiceSnapshot(), r.last)
        if (wasActive) {
            val after = r.log.drop(mark)
            val iStop = after.indexOf("audio.stop")
            val iAbort = after.indexOf("abort")
            val iPlayer = after.indexOf("player.stop")
            val iSnap = after.indexOfFirst { it.startsWith("snapshot:") }
            assertTrue("$after", iStop in 0 until iAbort)
            if (hadSession) {
                val iCancel = after.indexOfFirst { it.startsWith("cancel:") }
                assertTrue("$after", iAbort < iCancel && iCancel < iPlayer)
                assertEquals(cancelsBefore + 1, r.bridge.cancels.size)
            } else {
                assertTrue("$after", iAbort < iPlayer)
                assertEquals("セッション id がまだ無い", cancelsBefore, r.bridge.cancels.size)
            }
            if (iSnap >= 0) assertTrue("$after", iPlayer < iSnap) // 表示が変わらない(PREPARING → IDLE)ときは通知しない
            assertEquals(endedBefore + 1, r.listener.ended.size)
            assertEquals(false to "cancelled", r.listener.ended.last())
            assertTrue(r.player.stopCount >= 1)
        } else {
            assertEquals("終わっているセッションの取消では、ホストへ何も送らない", cancelsBefore, r.bridge.cancels.size)
            assertEquals("onEnded は 1 セッション 1 回", endedBefore, r.listener.ended.size)
        }
        // 取消のあとに届く古い結果・イベントは何も起こさない
        r.bridge.emitRaw(BridgeEvent.Reply(99, 0, "late", null, null, true, null))
        r.advance(120_000)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertFalse(r.audio.recording)
        assertEquals(VoiceSnapshot(), r.last)
    }

    @Test
    fun cancelDoesNotWaitForCommunication() {
        val r = Rig()
        r.audio.throwOnStop = throwOnStop
        r.bridge.chunkReply = { hang() }
        r.bridge.cancelReply = hang()
        driveTo(r, if (where == "IDLE") "LISTENING" else where)
        r.cancel() // 時間を進めない: マイクの停止は通信の完了に依存しない
        assertFalse(r.audio.recording)
        assertTrue(r.audio.stopCount >= 1)
        assertEquals(VoicePhase.IDLE, r.phase)
    }
}
