package io.github.ysogabe.miharashi.panel

/**
 * runtime が閉じられる前後に**成功した**セッション作成を、取りこぼさずホストから `DELETE` する [BridgeApi] の包み。
 *
 * [AndroidVoiceRuntime.close] はスレッドプールを `shutdown` し、セッションのループを終える。遅れて成功した作成の結果はセッションのスレッドへ
 * 配送されても捨てられうる(`VoiceSession` の「古い世代の作成成功は DELETE」まで届かない)。かつ `isClosed` の確認と結果の配送の間に
 * `close()` が完了する隙間があると、「閉じていない」と見て返した結果が終了済みのループに捨てられ、取消側も `session_id` を知らないので消せない。
 * そこで、作成が成功した `session_id` の**所有権をロックで同期**する。
 *
 * - [createSession] が成功したら、ロックの中で閉じたかを確認する。**閉じていれば**その場(呼び出したワーカー)で `DELETE` する。
 *   **閉じていなければ**その ID を「未取消」の集合 `pending` に登録して返す(`close()` 時に回収できるようにする)。
 *   確認と登録は [close] と同じロックなので、「閉じていないと見たのに回収もされない」隙間はない。
 * - [cancel]`(id)` は、その ID を `pending` から外してから内側へ渡す。`VoiceSession` は終えるとき(完了・失敗・取消・古い世代の作成成功)に必ず
 *   `bridge.cancel(id)` を呼ぶので、配送された作成は通常の取消で `pending` から外れる。引き渡しのための別の API は要らない。
 * - [close] はロックの中で閉じて、`pending` に残っている ID を**返す**(呼び出し側がワーカーのスレッドで [cancel] する。メインスレッドで通信しない)。
 *   セッションのスレッドが既に引き継いでいたものも含むが、DELETE は冪等(404 を受け入れる)なので二重になっても問題ない。
 * - `session_id` が形に合わなければ(パスに使えない)DELETE も登録もしない。DELETE の失敗は呼び出し元に伝えない(最良努力)。
 *   結果はそのまま呼び出し元へ返す。ほかの呼び出しはそのまま内側へ渡す。
 */
class DeleteOnCloseBridge(private val inner: BridgeApi) : BridgeApi by inner {
    private val lock = Any()
    private var closed = false
    private val pending = LinkedHashSet<String>()

    val isClosed: Boolean get() = synchronized(lock) { closed }

    override fun createSession(req: SessionRequest): SessionInfo {
        val info = inner.createSession(req)
        if (!VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)) return info
        val deleteNow = synchronized(lock) {
            if (closed) true else { pending += info.sessionId; false }
        }
        if (deleteNow) deleteQuietly(info.sessionId)
        return info
    }

    override fun cancel(sessionId: String) {
        synchronized(lock) { pending -= sessionId }
        inner.cancel(sessionId)
    }

    /** 閉じる(以後に成功した作成はその場で DELETE する)。まだ取り消されていない ID を返す(重複しない。呼び出し側がワーカーで [cancel] する)。冪等 */
    fun close(): List<String> = synchronized(lock) {
        closed = true
        pending.toList().also { pending.clear() }
    }

    private fun deleteQuietly(id: String) {
        try {
            inner.cancel(id)
        } catch (_: Exception) {
        }
    }
}
