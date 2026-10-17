package io.github.ysogabe.miharashi.panel

/**
 * しばらく触られなければ時計表示へ移る。時刻は呼び出し側が渡す(テストしやすいように)。
 * `timeoutMs` が 0 以下なら待機時計を表示しない。
 */
class IdleTimer(private val timeoutMs: Long, nowMs: Long) {
    private var lastTouchMs = nowMs

    fun touch(nowMs: Long) {
        lastTouchMs = nowMs
    }

    fun isSleeping(nowMs: Long): Boolean = timeoutMs > 0 && nowMs - lastTouchMs >= timeoutMs

    /** 時計表示までのミリ秒。無効設定なら null */
    fun untilSleepMs(nowMs: Long): Long? =
        if (timeoutMs > 0) (timeoutMs - (nowMs - lastTouchMs)).coerceAtLeast(0) else null
}
