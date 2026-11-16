package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作成をワーカーに積んだあと、ワーカーが実際に `createSession` を呼ぶ前に取消が来たら、作成の要求は送らない(設計書 §5.4・AC3)。
 * ワーカーの呼び出しを保留([FakeWorker.holdCalls])し、試験が順に実行させて、積んだあとの取消を再現する。
 */
class VoiceSessionCreateGateTest {
    private fun Rig.holdCreate() {
        worker.holdCalls = true
        session.start(StartReason.SWIPE, null, false)
        sim.settle() // doStart が作成をワーカーに積むところまで
        assertEquals(VoicePhase.PREPARING, phase)
        assertEquals(1, worker.heldCallCount)
        assertEquals("まだ作成の要求を送っていない", 0, bridge.created.size)
    }

    private fun Rig.assertNoErrorShown() {
        assertFalse(listener.snaps.any { it.state == VoiceState.ERROR })
        assertEquals(null, last.error)
    }

    @Test fun cancelAfterQueuingBeforeTheWorkerRunsNeverSendsCreate() {
        val r = Rig()
        r.holdCreate()
        r.cancel()
        assertEquals(VoicePhase.IDLE, r.phase)
        assertTrue(r.worker.runHeld())
        r.sim.settle()
        assertEquals("作成の要求は 0 回", 0, r.bridge.created.size)
        assertEquals("録音 0 回", 0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(listOf(false to "cancelled"), r.listener.ended)
        assertEquals("作成していないので DELETE もしない", emptyList<String>(), r.bridge.cancels)
        r.assertNoErrorShown()
    }

    @Test fun stopMicNowAfterQueuingBeforeTheWorkerRunsEndsAsCancelledWithoutError() {
        // cancel の post(世代を進める finish)が済む前に、作成の結果だけが先に届く並び。世代は同じままでも、取消として終えてエラーにしない
        val r = Rig()
        r.holdCreate()
        r.session.stopMicNow()
        assertTrue(r.worker.runHeld())
        r.sim.settle()
        assertEquals(0, r.bridge.created.size)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(listOf(false to "cancelled"), r.listener.ended)
        r.assertNoErrorShown()
    }

    @Test fun immediateReentryAfterCancelRunsOnlyTheNewCreate() {
        val r = Rig()
        r.holdCreate()
        r.cancel()
        r.start() // 取消のあとの即時の再入場。新しい doStart が作成をもう 1 つ積む
        assertEquals(VoicePhase.PREPARING, r.phase)
        assertEquals(2, r.worker.heldCallCount)
        assertTrue(r.worker.runHeld()) // 古い呼び出し: 作成を呼ばない
        assertEquals(0, r.bridge.created.size)
        assertTrue(r.worker.runHeld()) // 新しい呼び出し: 1 回だけ
        r.sim.settle()
        assertEquals(1, r.bridge.created.size)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
        assertEquals(1, r.listener.micOpenedAt.size)
        assertEquals(emptyList<String>(), r.bridge.cancels)
        r.assertNoErrorShown()
    }

    @Test fun withoutCancelTheHeldCreateRunsExactlyOnce() {
        val r = Rig()
        r.holdCreate()
        assertTrue(r.worker.runHeld())
        r.sim.settle()
        assertEquals(1, r.bridge.created.size)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals(1, r.audio.startCount)
        r.assertNoErrorShown()
    }

    /**
     * 指摘の窓: ワーカー内の照合を通ったあと、通信の層が照合する前に、取消(許可を進める)→ セッションのスレッドの finish(abortInFlight)が完了する。
     * 通信の層へ渡した照合が取消を拾い、作成の要求は 0 回。古い世代なので結果は黙って捨てる(エラーも出さない)
     */
    @Test fun aCancelCompletedAfterTheWorkersGateButBeforeTheTransportChecksNeverSendsCreate() {
        val r = Rig()
        r.holdCreate()
        r.bridge.beforeCurrentCheck = { r.cancel() } // 許可を進め、finish(abort・世代を進める)まで済ませる
        assertTrue(r.worker.runHeld())
        r.sim.settle()
        assertEquals("作成の要求は 0 回", 0, r.bridge.created.size)
        assertEquals("録音 0 回", 0, r.audio.startCount)
        assertEquals("finish の abort は済んでいる", 1, r.bridge.aborts)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(listOf(false to "cancelled"), r.listener.ended)
        assertEquals("作成していないので DELETE もしない", emptyList<String>(), r.bridge.cancels)
        r.assertNoErrorShown()
    }

    /** 同じ窓で、許可だけが進んだ(finish はまだ)並び: 世代は同じなので、取消として終え、エラーにしない */
    @Test fun aPermitRevokedAfterTheWorkersGateButBeforeTheTransportChecksEndsAsCancelledWithoutError() {
        val r = Rig()
        r.holdCreate()
        r.bridge.beforeCurrentCheck = { r.session.stopMicNow() }
        assertTrue(r.worker.runHeld())
        r.sim.settle()
        assertEquals(0, r.bridge.created.size)
        assertEquals(0, r.audio.startCount)
        assertEquals(VoicePhase.IDLE, r.phase)
        assertEquals(listOf(false to "cancelled"), r.listener.ended)
        r.assertNoErrorShown()
    }
}
