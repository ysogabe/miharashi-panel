package io.github.ysogabe.miharashi.panel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * キオスクとの同期(設計書 §5.6)。起動時と 5 秒ごとに `/status`、並行して `/events` の SSE。
 * SSE が切れたら 1、2、4 … 30 秒で再接続する。結果は UI スレッドで `onState` に渡す。
 * Activity の onResume で `start`、onPause で `stop` する。
 */
class KioskSync(
    private val client: KioskClient,
    /** 前の同期が最後に見せていた状態。設定画面から戻っても、表示を空から始めない */
    initial: PanelState,
    private val onState: (PanelState) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val poller = Executors.newSingleThreadScheduledExecutor { Thread(it, "kiosk-poll") }
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "kiosk-input") }

    @Volatile
    private var running = false
    private var sseThread: Thread? = null

    /** UI スレッドだけが触る */
    private var state = initial

    private fun update(f: (PanelState) -> PanelState) = main.post {
        // 止めたあとに届いた結果で、次の同期の表示を上書きしない
        if (!running) return@post
        state = f(state)
        onState(state)
    }

    private fun now() = SystemClock.elapsedRealtime()

    fun start() {
        if (running) return
        running = true
        poller.scheduleWithFixedDelay({ poll() }, 0, POLL_MS, TimeUnit.MILLISECONDS)
        sseThread = Thread({ sseLoop() }, "kiosk-events").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        client.closeEvents()
        sseThread?.interrupt()
        sseThread = null
        poller.shutdownNow()
        sender.shutdownNow()
    }

    private fun poll() {
        if (!running) return
        try {
            val s = client.status()
            update { it.withStatus(s, now()) }
        } catch (e: Exception) {
            update { it.withError(describe(e)) }
        }
    }

    private fun sseLoop() {
        val backoff = Backoff()
        while (running) {
            try {
                client.events { s ->
                    backoff.reset()
                    update { it.withStatus(s, now()) }
                }
            } catch (e: Exception) {
                if (!running) return
                update { it.withError(describe(e)) }
            }
            try {
                Thread.sleep(backoff.nextDelayMs())
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * キー(`/input`)、画面の選択(`/screen`)、モニターの切り替え(`/display`)、巡回の停止・再開(`/rotation`)を送る。結果(成功なら null、失敗なら理由)を UI スレッドで返す。
     * 失敗しても再送しない(二重送信を避ける)。成功したら、SSE を待たずに `/status` を読み直して表示を早く追従させる。
     * `isCurrent` は送信スレッドでも読む。自動操作にはAtomicBooleanによる取消判定を渡す。
     */
    fun send(action: Action, done: (String?) -> Unit, isCurrent: () -> Boolean = { true }) {
        if (!running) return done("停止中")
        sender.execute {
            if (!running || !isCurrent()) return@execute
            val err = try {
                when (action) {
                    is Action.Key -> client.input(action.name)
                    is Action.Display -> client.display(action.mode)
                    is Action.Screen -> client.screen(action.id)
                    is Action.Rotation -> client.rotation(action.mode)
                    Action.Settings -> Unit
                }
                null
            } catch (e: Exception) {
                describe(e)
            }
            main.post { if (running && isCurrent()) done(err) }
            if (err == null) poll()
        }
    }

    companion object {
        const val POLL_MS = 5_000L

        fun describe(e: Exception): String = when (e) {
            is KioskHttpException -> e.message ?: "HTTP ${e.code}"
            is java.net.SocketTimeoutException -> "応答がない"
            is java.net.ConnectException -> "接続できない"
            is java.net.UnknownHostException -> "ホストが見つからない"
            is org.json.JSONException -> "応答の形が違う"
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
    }
}
