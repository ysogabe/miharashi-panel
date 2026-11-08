package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** `Handler` を [SessionScheduler] にする。Looper が終わっていれば、post は黙って捨てられる。 */
class HandlerScheduler(private val handler: Handler) : SessionScheduler {
    override fun post(task: () -> Unit) {
        handler.post { task() }
    }

    override fun postDelayed(task: () -> Unit, delayMs: Long): Cancelable {
        val r = Runnable { task() }
        handler.postDelayed(r, maxOf(0L, delayMs))
        return Cancelable { handler.removeCallbacks(r) }
    }
}

/**
 * 本番の [VoiceRuntime](設計書 §5.4)。`HandlerThread("daidai-session")` + キャッシュ型スレッドプール(通信用。3 本以上同時)で
 * [VoiceSession] を動かす。マイクは [MicAudioSource]、再生は [WavPlayer]。録音・返答音声はファイルに書かない。
 * [close] は取り消しの `DELETE` を送れるよう、取り消しのあとにスレッドプールとループを閉じる。閉じる前後に成功した作成は、
 * 配送(ループが終わると捨てられる)に頼らず、[DeleteOnCloseBridge] がロックで所有権を同期して、閉じたあとなら通信のスレッドで、閉じる前なら [close] が回収して `DELETE` する。
 * マイクの停止([stopMicNow]・[close])は、マイクを開く許可を同期に取り消してから止める([VoiceSession.stopMicNow])。
 */
class AndroidVoiceRuntime(
    context: Context,
    settings: DaidaiSettings,
    listener: VoiceSessionListener,
) : VoiceRuntime {
    private val thread = HandlerThread("daidai-session").also { it.start() }
    private val scheduler = HandlerScheduler(Handler(thread.looper))
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "daidai-io").apply { isDaemon = true } }
    private val audio = MicAudioSource(context.applicationContext)
    private val player = WavPlayer(context.applicationContext)
    private val closed = AtomicBoolean(false)
    private val bridge = DeleteOnCloseBridge(BridgeClient(settings.url, settings.token))
    private val session = VoiceSession(
        audio = audio,
        bridge = bridge,
        player = player,
        clock = Clock { SystemClock.elapsedRealtime() },
        scheduler = scheduler,
        worker = ExecutorSessionWorker(executor, scheduler),
        listener = listener,
        appVersion = versionName(context.applicationContext),
    )

    override fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) {
        if (closed.get()) return
        session.start(reason, noticeId, autoStarted)
    }

    override fun retry() {
        if (closed.get()) return
        session.retry()
    }

    override fun cancel() {
        if (closed.get()) return
        session.cancel()
    }

    override fun stopMicNow() = session.stopMicNow()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.cancel() // closed が立つ前の cancel と同じ順序で、先に取り消しを積む(マイクを開く許可も、ここで同期に取り消す)
        session.stopMicNow()
        // 閉じる前に作成が成功して、まだ取り消されていないセッション(配送が間に合わない・捨てられるもの)をここで回収する。
        // 作成の成功とこの回収は DeleteOnCloseBridge のロックで同期しているので、取りこぼしはない。通信はメインスレッドで行わない
        for (id in bridge.close()) {
            try {
                executor.execute { try { bridge.cancel(id) } catch (_: Exception) { } }
            } catch (_: RuntimeException) { // RejectedExecutionException: 既に閉じている
            }
        }
        // 取り消しの DELETE がプールに積まれたあとで、新規の受け付けを止めてループを閉じる
        scheduler.post {
            executor.shutdown()
            thread.quitSafely()
        }
    }

    private companion object {
        fun versionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
