package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * runtime を閉じたあとに**遅れて成功した**セッション作成(AndroidVoiceRuntime のレビュー指摘)。
 * 本番の [AndroidVoiceRuntime.close] は、取消のあとにスレッドプールを `shutdown` し、セッションのループを終える。
 * ループが終わると、遅れて成功した作成の結果の配送は捨てられ、[VoiceSession] の「古い世代の作成成功は DELETE」まで届かない。
 * ここではループの終了を [GatedScheduler] で再現し、通信のスレッドで [DeleteOnCloseBridge] が DELETE することを確かめる。
 */
class LateCreateAfterCloseTest {
    /** `terminate` のあとに post されたものを捨てる(`HandlerThread.quitSafely` のあとの `Handler.post` と同じ) */
    private class GatedScheduler(private val inner: SessionScheduler) : SessionScheduler {
        @Volatile
        var terminated = false
        var dropped = 0

        override fun post(task: () -> Unit) {
            if (terminated) dropped++ else inner.post(task)
        }

        override fun postDelayed(task: () -> Unit, delayMs: Long): Cancelable {
            if (terminated) {
                dropped++
                return Cancelable { }
            }
            return inner.postDelayed(task, delayMs)
        }
    }

    /** 呼び出し(通信)を [runPending] まで実行せずに預かる。実行は通信のスレッド、結果の配送だけがセッションのスレッド */
    private class DeferredWorker(private val scheduler: SessionScheduler) : SessionWorker {
        private val pending = mutableListOf<() -> Unit>()
        private val deliveries = mutableListOf<() -> Unit>()

        override fun <T> run(call: () -> T, deliver: (Result<T>) -> Unit) {
            pending += {
                val result = runCatching(call)
                deliveries += { scheduler.post { deliver(result) } }
            }
        }

        /** 通信の呼び出しだけを実行する(結果の配送はまだ)。「作成は成功して戻ったが、配送の前に close が完了する」順序を作る */
        fun runCallsOnly() {
            val p = pending.toList()
            pending.clear()
            p.forEach { it() }
        }

        fun deliver() {
            val d = deliveries.toList()
            deliveries.clear()
            d.forEach { it() }
        }

        fun runPending() {
            runCallsOnly()
            deliver()
        }
    }

    private class LocalBridge : BridgeApi {
        val creates = mutableListOf<SessionRequest>()
        val cancels = mutableListOf<String>()
        override fun createSession(req: SessionRequest): SessionInfo {
            creates += req
            return SessionInfo("s-${creates.size}")
        }

        override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean) = ChunkAck(AckState.LISTENING, seq)
        override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {}
        override fun fetchReplyAudio(id: String, index: Int) = ByteArray(0)
        override fun cancel(id: String) { cancels += id }
        override fun abortInFlight() {} // 中断が間に合わなかった(作成は成功して戻る)
    }

    /** 本番の [AndroidVoiceRuntime] と同じ取消・終了の順序の runtime */
    private class Rig(private val wrap: Boolean) {
        val sim = Sim()
        val gated = GatedScheduler(sim)
        val worker = DeferredWorker(gated)
        val local = LocalBridge()
        val closed = AtomicBoolean(false)
        val bridge = DeleteOnCloseBridge(local)
        val audio = FakeAudio(sim, mutableListOf())
        val listener = RecordingListener(sim, mutableListOf())
        val session = VoiceSession(
            audio, if (wrap) bridge else local, FakePlayer(mutableListOf()), sim, gated, worker, listener,
        )

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            session.cancel()
            session.stopMicNow()
            if (wrap) bridge.close().forEach { runCatching { bridge.cancel(it) } } // AndroidVoiceRuntime.close: 未取消の ID を回収して DELETE
            gated.post { gated.terminated = true } // executor.shutdown(); thread.quitSafely()
        }
    }

    @Test fun aCreateThatSucceedsAfterTheRuntimeIsClosedIsDeletedByTheWorkerEvenThoughTheDeliveryIsDropped() {
        val r = Rig(wrap = true)
        r.session.start(StartReason.SWIPE, null, false)
        r.sim.settle() // doStart: 作成の通信が走り始める(まだ戻らない)
        assertEquals(VoicePhase.PREPARING, r.session.phase)
        r.close()
        r.sim.settle() // doCancel と、ループの終了
        assertTrue(r.gated.terminated)
        r.worker.runPending() // 遅れて作成が成功して戻る → 配送は捨てられる
        r.sim.settle()
        assertEquals("配送は捨てられた", 1, r.gated.dropped)
        assertEquals("通信のスレッドで、その場で DELETE", listOf("s-1"), r.local.cancels)
        assertEquals("マイクは開かない", 0, r.audio.startCount)
    }

    /** レビュー指摘の隙間: 作成が成功して戻ったあと、配送の前に close が完了しても、close が回収して DELETE する */
    @Test fun aCreateThatSucceededJustBeforeTheCloseButWasNotDeliveredYetIsStillDeleted() {
        val r = Rig(wrap = true)
        r.session.start(StartReason.SWIPE, null, false)
        r.sim.settle()
        r.worker.runCallsOnly() // 作成は成功して戻った(DeleteOnCloseBridge に登録された)。結果はまだ配送されない
        assertTrue("この時点ではまだ消さない", r.local.cancels.isEmpty())
        r.close() // 配送より先に close が完了する(ループも終わる)
        r.sim.settle()
        r.worker.deliver() // 終了済みのループへの配送は捨てられる
        r.sim.settle()
        assertEquals("配送は捨てられた", 1, r.gated.dropped)
        assertTrue("取消側は session_id を知らなくても、DELETE される", r.local.cancels.contains("s-1"))
        assertEquals("マイクは開かない", 0, r.audio.startCount)
    }

    @Test fun withoutTheWrapperTheLateCreateIsNeverDeleted() {
        // 対照: 包みがないと、ループの終了後に成功した作成は DELETE されない(ホストにセッションが残る)
        val r = Rig(wrap = false)
        r.session.start(StartReason.SWIPE, null, false)
        r.sim.settle()
        r.close()
        r.sim.settle()
        r.worker.runPending()
        r.sim.settle()
        assertEquals(1, r.gated.dropped)
        assertTrue("包みがなければ消えない(この試験が差を検出できることの確認)", r.local.cancels.isEmpty())
    }

    @Test fun aCreateThatSucceedsBeforeTheCloseIsDeletedByTheSessionAsBefore() {
        val r = Rig(wrap = true)
        r.session.start(StartReason.SWIPE, null, false)
        r.sim.settle()
        r.worker.runPending() // 作成が成功して戻り、配送が積まれる(close の前)
        r.close() // cancel が配送より後。許可が取り消されているのでマイクは開かない
        r.sim.settle()
        r.worker.runPending()
        assertEquals(0, r.audio.startCount)
        assertTrue("作成されたセッションは消される", r.local.cancels.contains("s-1"))
    }
}
