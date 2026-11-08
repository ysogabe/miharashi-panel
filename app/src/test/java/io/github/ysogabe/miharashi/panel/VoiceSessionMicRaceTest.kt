package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 実スレッド: セッションのスレッドが `audio.start` の最中のとき、別スレッドの `stopMicNow()` は開始の完了を待ち(排他)、
     * そのあとで止める。開始と取消が重なっても「止めたあとに録音が始まる」ことがない。
     */
    @Test fun stopMicNowWaitsForAnInProgressStartAndThenStopsTheMic() {
        val session = Executors.newSingleThreadExecutor { r -> Thread(r, "test-session") }
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-io").apply { isDaemon = true } }
        try {
            val inStart = CountDownLatch(1)
            val finishStart = CountDownLatch(1)
            val recording = AtomicBoolean(false)
            val stops = AtomicInteger()
            val startedAfterStop = AtomicBoolean(false)
            val audio = object : AudioSource {
                override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) {
                    inStart.countDown()
                    finishStart.await(10, TimeUnit.SECONDS)
                    if (stops.get() > 0) startedAfterStop.set(true)
                    recording.set(true)
                }

                override fun stop() {
                    stops.incrementAndGet()
                    recording.set(false)
                }

                override val recording: Boolean get() = recording.get()
            }
            val bridge = object : BridgeApi {
                override fun createSession(req: SessionRequest) = SessionInfo("s-1")
                override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean) = ChunkAck(AckState.LISTENING, seq)
                override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {}
                override fun fetchReplyAudio(id: String, index: Int) = ByteArray(0)
                override fun cancel(id: String) {}
                override fun abortInFlight() {}
            }
            val scheduler = object : SessionScheduler {
                override fun post(task: () -> Unit) {
                    session.execute { task() }
                }

                override fun postDelayed(task: () -> Unit, delayMs: Long) = Cancelable { } // タイマーは使わない
            }
            val s = VoiceSession(
                audio, bridge, FakePlayer(mutableListOf()), Clock { System.nanoTime() / 1_000_000L }, scheduler,
                ExecutorSessionWorker(pool, scheduler), RecordingListener(Sim(), mutableListOf()),
            )
            s.start(StartReason.SWIPE, null, false)
            assertTrue("セッションのスレッドが audio.start に入った", inStart.await(10, TimeUnit.SECONDS))
            val stopper = Thread { s.stopMicNow() }.apply { start() }
            stopper.join(300)
            assertTrue("開始の最中は stopMicNow が待たされる(許可の照合と開始が排他)", stopper.isAlive)
            assertEquals(0, stops.get())
            finishStart.countDown()
            stopper.join(10_000)
            assertFalse(stopper.isAlive)
            assertTrue("開始が済んだあとで止める", stops.get() >= 1)
            assertFalse("止めたあとに録音が始まっていない", recording.get())
            assertFalse(startedAfterStop.get())
        } finally {
            session.shutdownNow()
            pool.shutdownNow()
        }
    }
}
