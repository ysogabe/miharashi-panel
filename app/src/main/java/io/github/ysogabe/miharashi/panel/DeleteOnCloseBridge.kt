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
 * - [close] はロックの中で閉じて、`pending` に残っている `session_id` と、その 201 のハンドルを [ClosedPending] で**返す**
 *   (呼び出し側がワーカーのスレッドで [cancel] と [endConversation] する。メインスレッドで通信しない)。
 *   セッションのスレッドが既に引き継いでいたものも含むが、DELETE は冪等(404 を受け入れる)なので二重になっても問題ない。
 * - **会話のハンドル**(段階 2a-P): 作成の応答 `201` に `conversation.handle` があれば、`session_id` と一緒に持つ。閉じたあとに成功した作成は、`DELETE` に加えて
 *   [endConversation] もその場で(最良努力で)送る。閉じる前の作成は、[close] が `session_id` と一緒にハンドルも返す(呼び出し側がワーカーで送る)。
 * - [endConversation] は、**ホストへの送信を 1 か所で重複排除する**: 最近終えたハンドル(上限つき)にあれば送らず、無ければロックの中で記録してから(ロックの外で)内側へ渡す。
 *   複数の経路(会話の終わり・[VoiceSession] の古い 201 の後始末・[close] の回収)が同じハンドルを送ってもホストへは 1 回。ハンドルはログ・`toString` に出さない。
 * - `session_id` が形に合わなければ(パスに使えない)DELETE も登録もしない。DELETE の失敗は呼び出し元に伝えない(最良努力)。
 *   結果はそのまま呼び出し元へ返す。ほかの呼び出しはそのまま内側へ渡す。
 */
/** [close] の戻り値。まだ取り消されていない作成の `session_id` と、その 201 にあった会話のハンドル(呼び出し側がワーカーのスレッドで送る)。`toString` はどちらも出さない。 */
class ClosedPending(val sessionIds: List<String>, val handles: List<String>) {
    override fun toString() = "ClosedPending(sessions=${sessionIds.size}, handles=${handles.size})"
}

class DeleteOnCloseBridge(private val inner: BridgeApi) : BridgeApi by inner {
    private val lock = Any()
    private var closed = false

    /** まだ取り消されていない作成の `session_id` → その 201 の会話のハンドル(無ければ null) */
    private val pending = LinkedHashMap<String, String?>()

    /** 最近ホストへ終えた(送った)会話のハンドル。古いものから捨てる([RECENT_ENDED_MAX] 件) */
    private val recentEnded = LinkedHashSet<String>()

    val isClosed: Boolean get() = synchronized(lock) { closed }

    override fun createSession(req: SessionRequest): SessionInfo {
        val info = inner.createSession(req)
        val handle = info.conversation?.handle
        val validId = VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)
        val closedNow = synchronized(lock) {
            if (closed) true else { if (validId) pending[info.sessionId] = handle; false }
        }
        if (closedNow) {
            if (validId) deleteQuietly(info.sessionId)
            if (handle != null) endQuietly(handle)
        }
        return info
    }

    override fun cancel(sessionId: String) {
        synchronized(lock) { pending.remove(sessionId) }
        inner.cancel(sessionId)
    }

    /** 重複を 1 か所で抑える。形に合わないハンドルは記録せず内側へ渡す(内側が [IllegalArgumentException] を投げる)。送信はロックの外 */
    override fun endConversation(handle: String) {
        if (VoiceLimits.CONVERSATION_HANDLE_REGEX.matches(handle)) {
            val first = synchronized(lock) {
                if (!recentEnded.add(handle)) false else {
                    if (recentEnded.size > RECENT_ENDED_MAX) recentEnded.remove(recentEnded.first())
                    true
                }
            }
            if (!first) return
        }
        inner.endConversation(handle)
    }

    /**
     * 閉じる(以後に成功した作成はその場で DELETE する)。まだ取り消されていない `session_id` とそのハンドルを返す(重複しない。
     * 呼び出し側がワーカーで [cancel] と [endConversation] する)。冪等
     */
    fun close(): ClosedPending = synchronized(lock) {
        closed = true
        val ids = pending.keys.toList()
        val handles = pending.values.filterNotNull().distinct()
        pending.clear()
        ClosedPending(ids, handles)
    }

    private fun deleteQuietly(id: String) {
        try {
            inner.cancel(id)
        } catch (_: Exception) {
        }
    }

    private fun endQuietly(handle: String) {
        try {
            endConversation(handle) // 自分の endConversation を通す(ほかの経路との重複を抑える)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val RECENT_ENDED_MAX = 16
    }
}
