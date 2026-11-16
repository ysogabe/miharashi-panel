package io.github.ysogabe.miharashi.panel

enum class AutoSwitchAction {
    /** B へ切り替える。 */
    SWITCH,

    /** すでに B にいる。カードを更新するだけで、切替もセッション再開もしない。 */
    CARD_ONLY,

    /** 利用者が操作中。確定後に [AutoSwitchPolicy.onSettled] で一度だけ再評価する。 */
    DEFER,

    /** 切り替えない(通知は B に溜まる)。 */
    SKIP,
}

enum class AutoSwitchReason {
    /** ゲートが PRESENT */
    PRESENT,
    ON_DAIDAI_PAGE,
    USER_BUSY,
    DISABLED,
    GATE_ABSENT,
    GATE_UNKNOWN,
    LOW_PRIORITY,
    TOO_OLD,
}

/**
 * 自動切替が起点のマイクを開かない理由(設計書 §7、AC19)。
 * `AFTER_CONVERSATION_LIMIT`: 直前の自動起動の会話が、往復数の上限・受付の期限・ホストの `continue=false` で終わった(段階 2a-P、S2-U14)。
 */
enum class MicDenial { AUTO_LISTEN_OFF, AFTER_NO_SPEECH, AFTER_CONVERSATION_LIMIT, COOLDOWN, HOURLY_LIMIT }

/**
 * 判定の結果。`openMic` は切替(`action == SWITCH`)のときだけ意味があり、切替の判定とは別に歯止めを表す
 * (歯止めがあっても切り替え自体はできる)。`verdict` はゲートを呼んだときだけ入る(診断表示用)。
 */
data class AutoSwitchResult(
    val action: AutoSwitchAction,
    val reason: AutoSwitchReason,
    val verdict: GateVerdict? = null,
    val openMic: Boolean = false,
    val micDenial: MicDenial? = null,
)

/**
 * 判定の入力となる、いまの状況。設定の実体(`Settings`)は知らず、呼ぶ側が値を詰める。
 * `userBusy` は A のドラッグ中・アニメーション中。
 */
data class AutoSwitchContext(
    val nowMs: Long,
    val autoSwitchEnabled: Boolean,
    val autoListen: Boolean,
    val onDaidaiPage: Boolean,
    val userBusy: Boolean = false,
)

/**
 * 新着通知の候補から「切り替える／見送る(理由)」を決める(設計書 §4.1, §4.3, §7)。純 Kotlin、単一スレッドで使う。
 *
 * 知っているのは [PresenceGate] だけ。**ゲートが PRESENT のときだけ切り替える**(ABSENT も UNKNOWN も見送り)。
 * 見送った候補は取っておかない(ゲートを通らなかった通知の再評価はしない。O-9)。
 *
 * 判定の順序: B にいる → CARD_ONLY / 自動切替が無効 → SKIP / low・古い → SKIP / 操作中 → DEFER / ゲート。
 *
 * 自動切替が起点のマイク起動の歯止め([AutoSwitchResult.openMic]):
 * - `autoListen` が false なら開かない。
 * - 直前の自動マイク起動から [AUTO_MIC_COOLDOWN_MS] 未満なら開かない。
 * - 直近 [AUTO_MIC_WINDOW_MS] に [AUTO_MIC_MAX_PER_HOUR] 回起動していたら開かない(スライディングウィンドウ)。
 * - 直前の自動起動のセッションが `no_speech` で終わっていたら開かない([onSessionEnded])。
 *   解除: 利用者の手動操作起点のセッションが `no_speech` 以外で終わったとき。時間では解除しない
 *   (無人の部屋で通知のたびに聞き続けないため)。アプリを作り直すと(メモリのみ)解除される。
 * - 直前の自動起動の**会話**が、往復数の上限・受付の期限・ホストの `continue=false` で終わっていたら開かない([onAutoConversationLimited]。S2-U14)。
 *   無人の部屋で TV の音を拾い続けた見込みがあるため。解除の条件は無発話の見送りと同じ(手動のセッションが `no_speech` 以外で終わったとき)。
 *
 * 会話の複数往復(段階 2a-P)のうち、[onAutoMicStarted]・[onSessionEnded] に渡すのは**会話の最初の往復だけ**(続きの往復はクールダウン・回数・
 * 無発話の見送りに数えない。PRD R5。呼ぶ側 `DaidaiVoice` が選ぶ)。
 * 起動の記録は呼ぶ側が [onAutoMicStarted] で行う(実際にマイクを開いたときだけ数える)。
 */
class AutoSwitchPolicy(private val gate: PresenceGate) {
    private var pending: NoticeCandidate? = null
    private val autoMicStarts = ArrayDeque<Long>()

    /** 自動起動を見送っている理由(`AFTER_NO_SPEECH` か `AFTER_CONVERSATION_LIMIT`)。null なら見送っていない */
    private var held: MicDenial? = null

    /** 直近の判定(設定画面の診断表示用)。 */
    var lastResult: AutoSwitchResult? = null
        private set

    /** 再評価待ちの候補があるか。 */
    val hasPending: Boolean get() = pending != null

    fun evaluate(candidate: NoticeCandidate, ctx: AutoSwitchContext): AutoSwitchResult {
        val r = decide(candidate, ctx)
        pending = if (r.action == AutoSwitchAction.DEFER) candidate else null // 新しい候補が古い待ちを置き換える
        lastResult = r
        return r
    }

    /**
     * アニメーション・ドラッグが確定したときに呼ぶ。待ちの候補があれば**一度だけ**再評価する(なければ null)。
     * 候補の経過(`age_s` + 受信からの経過)が上限を超えていれば切り替えない。まだ操作中なら待ちを保つ。
     */
    fun onSettled(ctx: AutoSwitchContext): AutoSwitchResult? {
        val c = pending ?: return null
        if (ctx.userBusy) return AutoSwitchResult(AutoSwitchAction.DEFER, AutoSwitchReason.USER_BUSY)
        pending = null
        return decide(c, ctx).also { lastResult = it }
    }

    /** 待ちの候補を捨てる(onPause、接続先の変更)。 */
    fun cancelPending() {
        pending = null
    }

    /** 自動切替が起点でマイクを実際に開いたとき。 */
    fun onAutoMicStarted(nowMs: Long) {
        pruneStarts(nowMs)
        autoMicStarts.addLast(nowMs)
    }

    /**
     * 音声セッションが終わったとき。`autoStarted` は自動切替が起点のセッションか、`reason` は終了理由
     * ([REASON_NO_SPEECH] なら無発話)。自動起動のセッションは、その結果が直前の状態になる。
     */
    fun onSessionEnded(autoStarted: Boolean, reason: String?) {
        val noSpeech = reason == REASON_NO_SPEECH
        if (autoStarted) held = if (noSpeech) MicDenial.AFTER_NO_SPEECH else null else if (!noSpeech) held = null
    }

    /**
     * 自動起動で始まった会話が、往復数の上限・受付の期限・ホストの `continue=false` で終わったとき(S2-U14)。次の自動起動の見送りに数える。
     * 続きの無発話・エラー・取消・続けない結果では呼ばない。スワイプ・再試行の会話の上限でも呼ばない(呼ぶ側が選ぶ)。
     */
    fun onAutoConversationLimited() {
        held = MicDenial.AFTER_CONVERSATION_LIMIT
    }

    private fun decide(c: NoticeCandidate, ctx: AutoSwitchContext): AutoSwitchResult {
        if (ctx.onDaidaiPage) return AutoSwitchResult(AutoSwitchAction.CARD_ONLY, AutoSwitchReason.ON_DAIDAI_PAGE)
        if (!ctx.autoSwitchEnabled) return AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.DISABLED)
        if (c.notice.priority == NoticePriority.LOW) return AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.LOW_PRIORITY)
        if (c.ageMs(ctx.nowMs) > NoticeTracker.AUTO_SWITCH_MAX_AGE_S * 1000L) {
            return AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.TOO_OLD)
        }
        if (ctx.userBusy) return AutoSwitchResult(AutoSwitchAction.DEFER, AutoSwitchReason.USER_BUSY)
        val verdict = gate.evaluate(ctx.nowMs)
        return when (verdict.presence) {
            Presence.PRESENT -> {
                val denial = micDenial(ctx)
                AutoSwitchResult(AutoSwitchAction.SWITCH, AutoSwitchReason.PRESENT, verdict, denial == null, denial)
            }
            Presence.ABSENT -> AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.GATE_ABSENT, verdict)
            Presence.UNKNOWN -> AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.GATE_UNKNOWN, verdict)
        }
    }

    private fun micDenial(ctx: AutoSwitchContext): MicDenial? {
        if (!ctx.autoListen) return MicDenial.AUTO_LISTEN_OFF
        held?.let { return it }
        val now = ctx.nowMs
        pruneStarts(now)
        val last = autoMicStarts.lastOrNull()
        if (last != null && now - last < AUTO_MIC_COOLDOWN_MS) return MicDenial.COOLDOWN
        if (autoMicStarts.size >= AUTO_MIC_MAX_PER_HOUR) return MicDenial.HOURLY_LIMIT
        return null
    }

    /** ウィンドウ((now - 1 時間, now])の外の記録を捨てる。 */
    private fun pruneStarts(nowMs: Long) {
        while (autoMicStarts.isNotEmpty() && nowMs - autoMicStarts.first() >= AUTO_MIC_WINDOW_MS) autoMicStarts.removeFirst()
    }

    companion object {
        /** 自動切替が起点のマイク起動のクールダウン(設計書 §5.8) */
        const val AUTO_MIC_COOLDOWN_MS = 5 * 60_000L

        /** 1 時間あたりの自動マイク起動の最大回数(設計書 §5.8) */
        const val AUTO_MIC_MAX_PER_HOUR = 6
        const val AUTO_MIC_WINDOW_MS = 60 * 60_000L

        /** ブリッジの終了理由(無発話)。 */
        const val REASON_NO_SPEECH = "no_speech"
    }
}
