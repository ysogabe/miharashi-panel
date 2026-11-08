package io.github.ysogabe.miharashi.panel

import android.os.SystemClock

/**
 * B に入ったときの情報(後続の音声セッションが受け取る。設計書 §4.3, §7)。
 * T5 ではマイクを開かない。`openMic` は音声タスクが「開いてよいか」を決める材料で、`configured` が false なら開いてはならない。
 * 通知の本文は持たない(`noticeId` だけ)。
 */
data class DaidaiEntry(
    /** `SWIPE`(利用者のスワイプ)か `AUTO_NOTICE`(通知による自動切替)。それ以外では呼ばれない */
    val reason: PageReason,
    /** 自動切替が起点か(`AutoSwitchPolicy.onSessionEnded` の `autoStarted` に渡す) */
    val autoStarted: Boolean,
    /**
     * マイクを開いてよいか。ブリッジが設定済みで、自動切替ならクールダウンなどの歯止め・`daidai_auto_listen` を通ったとき true。
     * スワイプはブリッジが設定済みなら true。実際に開いたら、自動切替起点のときは `AutoSwitchPolicy.onAutoMicStarted` を呼ぶこと
     */
    val openMic: Boolean,
    /** 自動切替のとき、きっかけになった通知の id(セッション作成の `notice_id`)。スワイプなら null */
    val noticeId: String?,
    /** ブリッジの URL とトークンが両方入っているか */
    val configured: Boolean,
    /** 歯止めで開かない理由(自動切替のみ) */
    val micDenial: MicDenial? = null,
)

/** B への入場・退場の通知(音声セッションが実装する)。メインスレッドで呼ばれる。 */
interface DaidaiEntryListener {
    /** B に確定した(アニメーション終了時に一度だけ)。 */
    fun onEntered(entry: DaidaiEntry)

    /** B から離れた(右スワイプ・無操作での復帰・背面)。 */
    fun onLeft() {}
}

/**
 * だいだい連携の結線(設計書 §4.1, §4.2)。`KioskSync` の結果 → [NoticeTracker] → [AutoSwitchPolicy] → [PageController]、
 * 照度 → [AmbientLightGate]、B の表示データ。Android の View は知らず、描画・時計の解除・設定は呼び出し側が関数で渡す(単体テストできる)。
 * すべてメインスレッドで使う。Activity の `onResume` で [start]、`onPause` で [stop] を呼ぶ。
 *
 * - 基準: [start] のたびに通知の基準を取り直す。背面・設定画面にいた間、アプリ起動前、起動から最初の status までの通知では切り替えない。
 *   接続先(panel の URL・トークン)が変わったときは、通知の表示データも捨てる。
 * - 照度: [onLux] へ流す(購読はひとつ。`LightSensorReader` のコールバックに相乗り)。[stop] で照度を捨て、次の値が来るまで UNKNOWN。
 * - 切替: `SWITCH` なら待機時計を解除してから B へ。操作中(`DEFER`)は [PageListener.onIdle] で一度だけ再評価する。B にいるときはカードの更新だけ。
 * - 未読(R17、設計書 §4.4): 画面 A の手掛かり用の件数を [renderUnread] へ渡す。B に確定して [READ_AFTER_MS] 以上表示されたら
 *   (呼び出し側が [tick] を呼ぶ)、その時点の通知をすべて既読にする(メモリのみ)。それより前に B を離れたら既読にしない。
 *   B の表示中に新しく未読が増えたら、その時点から数え直す(見ていない通知を既読にしない)。
 *   ホストが既読を管理する(`ack: true`)なら、既読にしたとき最新の通知の id を [sendAck] で伝える(ベストエフォート)。
 * - Intent の extras は受け付けない(ページもマイクも、この型は外部入力から動かせない)。
 * - 通知の本文・トークンを logcat や例外メッセージに出さない。
 */
class DaidaiController(
    private val pages: PageController,
    private val settings: () -> DaidaiSettings,
    private val render: (DaidaiUiState) -> Unit,
    /** 待機時計が出ていれば解除する(`PanelView.wake`) */
    private val wakeClock: () -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** 画面 A に出す未読件数(0 なら何も出さない)。件数が変わったときにメインスレッドで呼ぶ */
    private val renderUnread: (Int) -> Unit = {},
    /** ホストへ既読を伝える(最新の通知の id)。ブロッキングの通信を UI スレッドで行わないこと(`KioskSync.ackDaidai`)。失敗は呼び出し側が捨てる */
    private val sendAck: (String) -> Unit = {},
) : PageListener {
    private var tracker = NoticeTracker()
    private var lux: Float? = null
    private val gate = AmbientLightGate(sample = { LuxSample(lux) }, config = { GateConfig.from(settings()) })
    val policy = AutoSwitchPolicy(gate)

    private var started = false
    private var endpoint: Any? = null
    private var lastStatus: KioskStatus? = null

    /** 再評価待ちの候補の通知 id */
    private var deferredId: String? = null

    /** 自動切替で B へ向かっているときの、入場に渡す情報 */
    private var autoEntry: AutoEntry? = null

    /** B を既読にする時刻(B に落ち着いている間だけ持つ)。未読が増えたら数え直す */
    private var readDueMs: Long? = null
    private var shownUnread = -1

    private class AutoEntry(val noticeId: String, val openMic: Boolean, val micDenial: MicDenial?)

    /** 音声セッション(T6 以降)が入場・退場を受け取る口 */
    var entryListener: DaidaiEntryListener? = null

    /** ブリッジが設定済みか(未設定ならマイクは起動しない) */
    val configured: Boolean get() = settings().isConfigured()

    init {
        pages.addListener(this)
    }

    /**
     * 前面に出た。通知の基準を取り直す。`initial` は前の同期が最後に見せていた状態(これは新しい status ではないので数えない)。
     * `endpoint` は panel の接続先(URL とトークン)で、前回と違えば通知の表示データも捨てる。
     * `KioskSync` を始める**前**に呼ぶこと(最初の status が基準になるように)。
     */
    fun start(initial: PanelState, endpoint: Any?) {
        if (started) stop()
        started = true
        if (endpoint != this.endpoint) tracker = NoticeTracker() else tracker.rebaseline()
        this.endpoint = endpoint
        lastStatus = initial.status
        lux = null
        gate.reset()
        policy.cancelPending()
        deferredId = null
        autoEntry = null
        // 背面から戻るときは常に A(PageController.onResume)。B の既読の予約は持ち越さない
        readDueMs = null
        ackedRev = null
        shownUnread = -1
        render(buildUi())
        publishUnread()
    }

    /** 背面に回った。照度を捨て(次の値までは UNKNOWN)、再評価待ちも捨てる。 */
    fun stop() {
        started = false
        lux = null
        gate.reset()
        policy.cancelPending()
        deferredId = null
        autoEntry = null
        readDueMs = null
    }

    /** 照度の値(`LightSensorReader` のコールバック)。不明・不正値は null。 */
    fun onLux(value: Float?) {
        if (!started) return
        lux = validLux(value)
        gate.observe(value, nowMs())
    }

    /** `KioskSync` の結果。新しい status のときだけ通知を調べる(エラーでの再配信は数えない)。 */
    fun onKioskState(state: PanelState) {
        if (!started) return
        val status = state.status
        if (status === lastStatus) return
        lastStatus = status
        val unreadBefore = tracker.unreadCount
        val candidate = tracker.onStatus(status?.daidai, nowMs())
        render(buildUi())
        // B を見ている間に未読が増えた: 見えるようになってから READ_AFTER_MS たったら既読にする
        if (pages.settledPage == Page.B && tracker.unreadCount > unreadBefore) readDueMs = nowMs() + READ_AFTER_MS
        publishUnread()
        if (candidate == null) return
        val result = policy.evaluate(candidate, context())
        deferredId = if (result.action == AutoSwitchAction.DEFER) candidate.notice.id else null
        act(result, candidate.notice.id)
    }

    /** 落ち着いた。操作中に見送った候補があれば、ここで一度だけ再評価する。 */
    override fun onIdle() {
        if (!started || !policy.hasPending) return
        val id = deferredId ?: return
        val result = policy.onSettled(context()) ?: return
        if (result.action == AutoSwitchAction.DEFER) return // まだ操作中。待ちは保たれている
        deferredId = null
        act(result, id)
    }

    /**
     * 時間を進める(B に落ち着いている間、呼び出し側が定期的に呼ぶ)。B が確定して [READ_AFTER_MS] 以上たっていれば、
     * その時点の通知をすべて既読にする。A にいる間・B を離れたあとは何もしない。
     */
    fun tick(nowMs: Long) {
        if (!started) return
        val due = readDueMs ?: return
        if (pages.settledPage != Page.B) { readDueMs = null; return }
        if (nowMs < due) return
        readDueMs = null
        val hadUnread = tracker.unreadCount > 0
        val ackHost = tracker.ack
        tracker.markAllSeen()
        if (hadUnread && ackHost) {
            ackedRev = tracker.rev
            tracker.latestNotice?.id?.let(sendAck)
        }
        publishUnread()
    }

    /** ack 対応のホストへ既読を伝えた時点の `rev`。この `rev` の間はホストの `unseen` の更新を待たずに 0 として扱う */
    private var ackedRev: Long? = null

    private fun unread(): Int {
        val rev = tracker.rev
        if (tracker.ack && rev != null && rev == ackedRev) return 0
        return tracker.unreadCount
    }

    private fun publishUnread() {
        val n = unread()
        if (n == shownUnread) return
        shownUnread = n
        renderUnread(n)
    }

    override fun onSettled(page: Page, reason: PageReason) {
        if (page == Page.B) readDueMs = nowMs() + READ_AFTER_MS
        if (page == Page.A) {
            readDueMs = null
            autoEntry = null
            entryListener?.onLeft()
            return
        }
        val auto = autoEntry.takeIf { reason == PageReason.AUTO_NOTICE }
        autoEntry = null
        val cfg = configured
        entryListener?.onEntered(
            when (reason) {
                PageReason.AUTO_NOTICE -> DaidaiEntry(
                    reason, autoStarted = true, openMic = cfg && auto?.openMic == true,
                    noticeId = auto?.noticeId, configured = cfg, micDenial = auto?.micDenial,
                )
                else -> DaidaiEntry(reason, autoStarted = false, openMic = cfg, noticeId = null, configured = cfg)
            },
        )
    }

    private fun act(result: AutoSwitchResult, noticeId: String) {
        if (result.action != AutoSwitchAction.SWITCH) {
            AutoSwitchDiagnostics.record(result)
            return
        }
        val now = nowMs()
        wakeClock() // 待機時計が出ていれば先に解除する(時計中でも動く。§2.2)
        if (pages.goTo(Page.B, PageReason.AUTO_NOTICE, now)) {
            autoEntry = AutoEntry(noticeId, result.openMic, result.micDenial)
            AutoSwitchDiagnostics.record(result)
        } else {
            // 判定の直前に操作中でないことを確かめているので通常は起きない。切り替えられなかったことだけ残す
            AutoSwitchDiagnostics.record(AutoSwitchResult(AutoSwitchAction.SKIP, AutoSwitchReason.USER_BUSY))
        }
    }

    private fun context(): AutoSwitchContext {
        val s = settings()
        return AutoSwitchContext(
            nowMs = nowMs(),
            autoSwitchEnabled = s.autoSwitch,
            autoListen = s.autoListen,
            onDaidaiPage = pages.settledPage == Page.B,
            userBusy = pages.userBusy,
        )
    }

    companion object {
        /** B に確定してから既読にするまでの時間(R17、設計書 §4.4) */
        const val READ_AFTER_MS = 2_000L
    }

    /** B の表示データ。最新の通知 1 件と「他 N 件」、通知のポーズ、未設定の案内。音声の状態は T6 以降が足す */
    private fun buildUi(): DaidaiUiState {
        val notices = tracker.notices
        return DaidaiUiState.forSettings(configured).copy(
            noticePose = tracker.avatarPose,
            notice = tracker.latestNotice,
            moreNotices = (notices.size - 1).coerceAtLeast(0),
        )
    }
}
