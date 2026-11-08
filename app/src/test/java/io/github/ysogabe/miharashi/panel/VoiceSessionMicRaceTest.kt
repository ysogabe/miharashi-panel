package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 取消とマイクの開始の競合(設計書 §5.4, §7)。作成成功の処理(`onCreated → openMic`)がセッションのキューに積まれたまま
 * `cancel()` / `stopMicNow()` / runtime の close が来ても、**そのあとでマイクを開かない**こと。
 * 取消は呼び出しスレッドから同期にマイクを開く許可を取り消すので、キューの順序(作成成功が先)に左右されない。
 */
class VoiceSessionMicRaceTest {
    /** 作成の応答の配送(`onCreated`)だけがキューに積まれた状態まで進める */
    private fun Rig.startHoldingCreated(auto: Boolean = false) {
        session.start(StartReason.SWIPE, null, auto)
        var guard = 0
        // 作成の通信が走り、完了して、その応答の配送(onCreated)がキューに積まれたところで止める(配送はまだ実行しない)
        while (bridge.created.isEmpty() || worker.inflightCount > 0) check(sim.step() && guard++ < 50) { "doStart まで進まない" }
        assertEquals("まだマイクを開いていない", 0, audio.startCount)
        assertEquals(VoicePhase.PREPARING, phase)
    }

    private fun Rig.assertNeverOpenedAndCleanedUp() {
        assertEquals("マイクを開かない", 0, audio.startCount)
        assertFalse(audio.recording)
        assertEquals(VoicePhase.IDLE, phase)
        assertEquals("作成が成功していたホストのセッションを消す", listOf("s-1"), bridge.cancels)
        assertEquals("開始音も鳴らさない", 0, listener.micOpenedAt.size)
        assertEquals("取消として 1 回だけ終わる", listOf(false to "cancelled"), listener.ended)
        assertFalse(last.micActive)
    }

    @Test fun cancelWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rig()
        r.startHoldingCreated()
        r.session.cancel() // キューは [onCreated, doCancel]。doCancel より前に onCreated が走る
        r.sim.settle()
        r.assertNeverOpenedAndCleanedUp()
    }

    @Test fun stopMicNowWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rig()
        r.startHoldingCreated()
        r.session.stopMicNow() // cancel の post より前に、呼び出しスレッドから
        assertEquals("まだ録音していないので audio.stop は空振りでもよい", 0, r.audio.startCount)
        r.sim.settle()
        r.assertNeverOpenedAndCleanedUp()
    }

    @Test fun theRuntimeCloseSequenceWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rig()
        r.startHoldingCreated()
        r.session.cancel() // AndroidVoiceRuntime.close: cancel → stopMicNow
        r.session.stopMicNow()
        r.sim.settle()
        r.assertNeverOpenedAndCleanedUp()
    }

    @Test fun theLeaveSequenceWhileTheCreatedResultIsQueuedNeverOpensTheMic() {
        val r = Rig()
        r.startHoldingCreated()
        r.session.cancel() // DaidaiVoice.cancel: rt.cancel() → rt.stopMicNow()
        r.session.stopMicNow()
        r.sim.advance(10_000)
        r.assertNeverOpenedAndCleanedUp()
    }

    @Test fun aNewStartAfterTheCancelStillOpensTheMicForTheNewSessionOnly() {
        val r = Rig()
        r.startHoldingCreated()
        r.session.cancel()
        r.session.stopMicNow()
        r.session.start(StartReason.SWIPE, null, false) // 離脱してすぐ戻った。許可は新しいセッションの開始時の値
        r.sim.settle()
        assertEquals("古い作成成功ではなく、新しいセッションの分だけ開く", 1, r.audio.startCount)
        assertTrue(r.audio.recording)
        assertEquals(VoicePhase.LISTENING, r.phase)
        assertEquals("古いセッションは消す", listOf("s-1"), r.bridge.cancels)
        assertEquals(1, r.listener.micOpenedAt.size)
    }

    @Test fun cancelBeforeStartIsProcessedStillStartsNothingForThatStart() {
        val r = Rig()
        r.session.start(StartReason.SWIPE, null, false) // doStart がまだ走っていない
        r.session.cancel()
        r.session.stopMicNow()
        r.sim.settle()
        // doStart は取消の「後」に許可を控える(新しい開始として扱う)が、続く doCancel が必ず終わらせる
        assertEquals(0, r.audio.startCount)
        assertFalse(r.audio.recording)
        assertEquals(VoicePhase.IDLE, r.phase)
    }

    @Test fun stopMicNowAfterTheMicIsOpenStillStopsIt() {
        val r = Rig()
        r.startAndListen()
        assertTrue(r.audio.recording)
        r.session.stopMicNow()
        assertFalse("開いたあとの stopMicNow は録音を止める", r.audio.recording)
    }

    @Test fun anAudioStopThatThrowsInStopMicNowDoesNotEscape() {
        val r = Rig()
        r.startAndListen()
        r.audio.throwOnStop = true
        r.session.stopMicNow() // 例外を呼び出し元(メインスレッド)に出さない
        assertFalse(r.audio.recording)
    }

    // ---- 実スレッド: 取消側(メインスレッドを模す)は audio.start の完了を待たない

    /**
     * セッションのスレッドが `audio.start` の中で止まっている状態を作る実スレッドの組み立て。
     * [cooperative] が true のフェイクは [AudioSource.start] の `isCurrent` を見て、取消されていれば録音を始めずに戻る(MicAudioSource と同じ)。
     * false のフェイクは取消を見ずに録音を始めてしまう(VoiceSession の「戻ったあとの照合 + stop」で止まることの確認)。
     */
    private class RealRig(cooperative: Boolean) {
        val sessionExec = Executors.newSingleThreadExecutor { r -> Thread(r, "test-session") }
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-io").apply { isDaemon = true } }
        val inStart = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        val isRecording = AtomicBoolean(false)
        val stops = AtomicInteger()
        val starts = AtomicInteger()
        val pcmEmitted = AtomicInteger()
        val deleted = CopyOnWriteArrayList<String>()
        val listener = RecordingListener(Sim(), mutableListOf())

        private val audio = object : AudioSource {
            private fun doStart(onPcm: (ByteArray) -> Unit, isCurrent: () -> Boolean) {
                starts.incrementAndGet()
                inStart.countDown()
                finishStart.await(10, TimeUnit.SECONDS)
                if (cooperative && !isCurrent()) return
                isRecording.set(true)
                pcmEmitted.incrementAndGet()
                onPcm(ByteArray(3200)) // 録音が始まっていれば、音も出てしまう(取消の後は VoiceSession が捨てること)
            }

            override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) = doStart(onPcm) { true }
            override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit, isCurrent: () -> Boolean) =
                if (cooperative) doStart(onPcm, isCurrent) else start(onPcm, onError)

            override fun stop() {
                stops.incrementAndGet()
                isRecording.set(false)
            }

            override val recording: Boolean get() = isRecording.get()
        }
        private val bridge = object : BridgeApi {
            override fun createSession(req: SessionRequest) = SessionInfo("s-1")
            override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean) = ChunkAck(AckState.LISTENING, seq)
            override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {}
            override fun fetchReplyAudio(id: String, index: Int) = ByteArray(0)
            override fun cancel(id: String) { deleted += id }
            override fun abortInFlight() {}
        }
        private val scheduler = object : SessionScheduler {
            override fun post(task: () -> Unit) {
                try { sessionExec.execute { task() } } catch (_: RuntimeException) { }
            }

            override fun postDelayed(task: () -> Unit, delayMs: Long) = Cancelable { } // タイマーは使わない
        }
        val session = VoiceSession(
            audio, bridge, FakePlayer(mutableListOf()), Clock { System.nanoTime() / 1_000_000L }, scheduler,
            ExecutorSessionWorker(pool, scheduler), listener,
        )

        /** セッションのスレッドが `audio.start` の中で止まるまで進める */
        fun startAndWaitInsideAudioStart() {
            session.start(StartReason.SWIPE, null, false)
            assertTrue("セッションのスレッドが audio.start に入った", inStart.await(10, TimeUnit.SECONDS))
        }

        /** [block] を別スレッド(メインを模す)で呼び、戻るまでの時間(ms)を返す。[LIMIT_MS] を超えたら失敗 */
        fun timedFromMain(block: () -> Unit): Long {
            var elapsed = -1L
            val t = Thread {
                val t0 = System.nanoTime()
                block()
                elapsed = (System.nanoTime() - t0) / 1_000_000L
            }.apply { start() }
            t.join(5_000)
            assertFalse("audio.start の完了を待たずに戻る(5 秒待っても戻らない)", t.isAlive)
            assertTrue("取消側の呼び出しが ${LIMIT_MS} ms 以内に戻る(実測 $elapsed ms)", elapsed in 0..LIMIT_MS)
            return elapsed
        }

        /** ラッチを解放して、セッションのスレッドが取消として終えるまで待つ */
        fun releaseAndSettle() {
            finishStart.countDown()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (deleted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            sessionExec.submit { }.get(10, TimeUnit.SECONDS) // 積まれた処理がすべて済むまで
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            sessionExec.submit { }.get(10, TimeUnit.SECONDS)
        }

        fun assertCleanlyCancelled() {
            assertFalse("録音が残らない", isRecording.get())
            assertFalse(audio.recording)
            assertTrue("止めている", stops.get() >= 1)
            assertEquals("マイクを開いた通知(開始音)を出さない", 0, listener.micOpenedAt.size)
            assertEquals("取消として 1 回だけ終わる", listOf(false to "cancelled"), listener.ended)
            assertEquals("ホストのセッションを 1 回だけ消す", listOf("s-1"), deleted.toList())
            val last = listener.snaps.lastOrNull()
            assertTrue("マイク使用中の表示が残らない", last == null || !last.micActive)
        }

        fun close() {
            sessionExec.shutdownNow()
            pool.shutdownNow()
        }

        companion object {
            const val LIMIT_MS = 200L
        }
    }

    private fun realRigTest(cooperative: Boolean, body: (RealRig) -> Unit) {
        val rig = RealRig(cooperative)
        try {
            body(rig)
        } finally {
            rig.finishStart.countDown()
            rig.close()
        }
    }

    @Test fun cancelDoesNotWaitForAnInProgressAudioStartAndNothingIsLeftRecording() = realRigTest(cooperative = false) { r ->
        r.startAndWaitInsideAudioStart()
        r.timedFromMain { r.session.cancel() }
        r.releaseAndSettle()
        r.assertCleanlyCancelled()
    }

    @Test fun stopMicNowDoesNotWaitForAnInProgressAudioStartAndNothingIsLeftRecording() = realRigTest(cooperative = false) { r ->
        r.startAndWaitInsideAudioStart()
        r.timedFromMain { r.session.stopMicNow() }
        // stopMicNow はホストへ何も送らず、状態の終了は cancel と併せて使う(DaidaiVoice / close の順序)
        r.timedFromMain { r.session.cancel() }
        r.releaseAndSettle()
        r.assertCleanlyCancelled()
    }

    @Test fun theCloseSequenceDoesNotWaitForAnInProgressAudioStartAndNothingIsLeftRecording() = realRigTest(cooperative = false) { r ->
        r.startAndWaitInsideAudioStart()
        // AndroidVoiceRuntime.close: cancel → stopMicNow(→ 以後に executor / ループの終了)。メインスレッドを模す 1 本の呼び出し
        r.timedFromMain {
            r.session.cancel()
            r.session.stopMicNow()
        }
        r.releaseAndSettle()
        r.assertCleanlyCancelled()
    }

    @Test fun anAudioSourceThatHonoursIsCurrentNeverStartsRecordingAfterTheCancel() = realRigTest(cooperative = true) { r ->
        r.startAndWaitInsideAudioStart()
        r.timedFromMain {
            r.session.cancel()
            r.session.stopMicNow()
        }
        r.releaseAndSettle()
        assertEquals("取消を見る実装は録音を始めない(音も出ない)", 0, r.pcmEmitted.get())
        r.assertCleanlyCancelled()
    }

    @Test fun pcmEmittedByANonCooperativeSourceAfterTheCancelIsDropped() = realRigTest(cooperative = false) { r ->
        r.startAndWaitInsideAudioStart()
        r.timedFromMain { r.session.cancel() }
        r.releaseAndSettle()
        assertEquals("取消を見ない実装は音を出してしまう", 1, r.pcmEmitted.get())
        r.assertCleanlyCancelled() // それでも VoiceSession は送らず(世代が違う)、録音も残さない
    }
}
