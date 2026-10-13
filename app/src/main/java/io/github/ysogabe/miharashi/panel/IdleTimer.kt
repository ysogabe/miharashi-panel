package io.github.ysogabe.miharashi.panel

/**
 * しばらく触られなければ操作盤を消灯する(焼き付き・残像を避ける)。時刻は呼び出し側が渡す(テストしやすいように)。
 * `timeoutMs` が 0 以下なら消灯しない。
 */
class IdleTimer(private val timeoutMs: Long, nowMs: Long) {
    private var lastTouchMs = nowMs

    fun touch(nowMs: Long) {
        lastTouchMs = nowMs
    }

    fun isSleeping(nowMs: Long): Boolean = timeoutMs > 0 && nowMs - lastTouchMs >= timeoutMs

    /** 次に消灯するまでのミリ秒。消灯しない設定なら null */
    fun untilSleepMs(nowMs: Long): Long? =
        if (timeoutMs > 0) (timeoutMs - (nowMs - lastTouchMs)).coerceAtLeast(0) else null
}
