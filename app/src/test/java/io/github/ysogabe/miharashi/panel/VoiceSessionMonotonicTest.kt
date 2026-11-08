package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 状態は後退しない(AC6)。ack と SSE をランダムな順序・タイミングで入れても、進行中の内部状態の順序が下がらない。 */
class VoiceSessionMonotonicTest {
    private fun rank(p: VoicePhase) = when (p) {
        VoicePhase.IDLE -> 0
        VoicePhase.PREPARING -> 1
        VoicePhase.LISTENING -> 2
        VoicePhase.HEARING -> 3
        VoicePhase.WAITING -> 4
        VoicePhase.REPLYING -> 5
        VoicePhase.DONE, VoicePhase.ERROR -> 6
    }

    @Test
    fun randomInputsNeverRegressTheState() {
        for (seed in 1..300) {
            val rnd = Random(seed.toLong())
            val r = Rig(guardMs = 0)
            // 応答の state と遅延は呼び出しごとにランダム(ack が前後しても、後退しない)
            r.bridge.chunkReply = { c ->
                val st = AckState.values()[rnd.nextInt(3).let { if (it == 2 && rnd.nextInt(4) != 0) 1 else it }]
                ok(ChunkAck(st, c.seq), latencyMs = rnd.nextInt(700).toLong())
            }
            r.start()
            var maxRank = rank(r.phase)
            var maxDisplay = 0
            val order = listOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.HEARING, VoiceState.WAITING, VoiceState.REPLYING)
            repeat(40) {
                if (!r.phase.isActive) return@repeat
                when (rnd.nextInt(9)) {
                    0 -> r.speech()
                    1 -> r.waiting()
                    2 -> r.transcript("t${rnd.nextInt(100)}")
                    3 -> if (rnd.nextInt(3) == 0) r.reply("r", audio = if (rnd.nextBoolean()) null else ReplyAudioInfo("wav", 24_000, 2000, 3000))
                    4 -> r.hostEmit { BridgeEvent.State(it, HostState.LISTENING) }
                    5 -> if (rnd.nextInt(10) == 0) r.hostError("asr_failed")
                    6 -> r.talk(rnd.nextInt(400) + 20)
                    7 -> r.advance(rnd.nextInt(1500).toLong())
                    else -> {
                        // 古い seq の再送・重複
                        r.bridge.emitRaw(BridgeEvent.State(rnd.nextInt(3), HostState.entries[rnd.nextInt(3)]))
                        r.sim.settle()
                    }
                }
                if (r.phase.isActive) {
                    val rk = rank(r.phase)
                    assertTrue("seed=$seed phase=${r.phase} max=$maxRank", rk >= maxRank)
                    maxRank = rk
                }
            }
            // 画面に出た状態の列も、終わるまで並びを下げない
            var terminal = false
            for (s in r.listener.snaps) {
                if (s.state == VoiceState.ERROR) terminal = true
                if (terminal) break
                val idx = order.indexOf(s.state)
                if (idx < 0) continue
                if (s.state == VoiceState.IDLE && maxDisplay > 0) { terminal = true; continue } // DONE → IDLE
                assertTrue("seed=$seed ${r.states()}", idx >= maxDisplay)
                maxDisplay = idx
            }
            // 何をしても、録音は終わった状態では止まっている
            if (!r.phase.isActive) assertEquals("seed=$seed", false, r.audio.recording)
            r.cancel()
            assertEquals(false, r.audio.recording)
        }
    }

    @Test
    fun everyActiveStateHasAFiniteExit() {
        // どこで止まっても、ホストが何も言わなければ 120 秒以内に必ず進行中でなくなる
        val setups = listOf<(Rig) -> Unit>(
            { it.bridge.createReply = { hang() }; it.start() },
            { it.bridge.chunkReply = { hang() }; it.startAndListen(); it.talk(500) },
            { it.startAndListen(); it.talk(500) },
            { it.ackState = AckState.SPEECH; it.startAndListen(); it.talk(500) },
            { it.startAndListen(); it.talk(500); it.waiting() },
            { it.bridge.fetchReply = { hang() }; it.startAndListen(); it.reply() },
            { it.startAndListen(); it.reply() },
        )
        for ((i, setup) in setups.withIndex()) {
            val r = Rig()
            setup(r)
            // マイクが開いているものは、無音でも音があっても進める(無音は mic_silent / 無発話で終わる)
            var t = 0
            while (r.phase.isActive && t < 130_000) {
                if (r.audio.recording) r.talk(20) else r.advance(20)
                t += 20
            }
            assertTrue("setup=$i phase=${r.phase}", !r.phase.isActive)
            assertTrue("setup=$i t=$t", t <= 120_000)
            assertEquals(false, r.audio.recording)
        }
    }
}
