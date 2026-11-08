package io.github.ysogabe.miharashi.panel

/**
 * runtime が閉じられたあとに**成功した**セッション作成を、通信のスレッド(ワーカー)でその場で `DELETE` する [BridgeApi] の包み。
 *
 * [AndroidVoiceRuntime.close] はスレッドプールを `shutdown` し、セッションのループを終える。遅れて成功した作成の結果は
 * セッションのスレッドへ配送されても捨てられうる(`VoiceSession` の「古い世代の作成成功は DELETE」まで届かない)ので、
 * 配送に頼らず、作成を呼んだスレッドで `isClosed` を確かめて消す。ホストにセッションを残さない(認識テキストを早く手放させる)。
 *
 * - `isClosed` が true になったあとに `createSession` が成功して戻った場合だけ、`session_id` の形を確かめて [BridgeApi.cancel] を呼ぶ。
 *   結果はそのまま呼び出し元へ返す(配送されれば `VoiceSession` が世代の違う作成として改めて DELETE する。DELETE は冪等)。
 * - DELETE の失敗は呼び出し元に伝えない(最良努力)。ほかの呼び出しはそのまま内側へ渡す。
 */
class DeleteOnCloseBridge(
    private val inner: BridgeApi,
    private val isClosed: () -> Boolean,
) : BridgeApi by inner {
    override fun createSession(req: SessionRequest): SessionInfo {
        val info = inner.createSession(req)
        if (isClosed() && VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)) {
            try {
                inner.cancel(info.sessionId)
            } catch (_: Exception) {
            }
        }
        return info
    }
}
