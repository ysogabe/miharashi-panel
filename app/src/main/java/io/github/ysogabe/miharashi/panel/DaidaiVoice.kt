package io.github.ysogabe.miharashi.panel

/**
 * 音声セッション一式(スレッド・通信・マイク・再生)。本番は [AndroidVoiceRuntime]、試験ではフェイク。
 * **マイクを開く経路は [start](と [retry])だけ**で、`VoiceSession.start` に行き着く。
 * すべてのメソッドはどのスレッドから呼んでもよく、ノンブロッキング。
 */
interface VoiceRuntime {
    /** 新しいセッションを始める(進行中なら無視される。呼ぶ側が先に [cancel] する) */
    fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean)

    /** ERROR 表示のあとの再試行(利用者のタップだけが呼ぶ)。ERROR でなければ何もしない。`autoStarted = false` で始める */
    fun retry()

    /** セッションを取り消す(マイク停止・release、通信の中断、`DELETE`)。冪等 */
    fun cancel()

    /** セッションのスレッドを介さずにマイクを止める(最後の防衛線)。冪等 */
    fun stopMicNow()

    /** 取り消してから、スレッドなどを片づける。以後は使えない */
    fun close()
}

fun interface VoiceRuntimeFactory {
    fun create(settings: DaidaiSettings, listener: VoiceSessionListener): VoiceRuntime
}

/**
 * B の確定とマイクの結線(設計書 §4.3, §5.4, §5.5, §7)。[DaidaiController] の入場・退場の通知([DaidaiEntryListener])を受けて
 * [VoiceRuntime] のセッションを始め・止め、セッションの状態を画面([render])と [PageController.sessionActive] に流す。
 * 通知の本文・認識テキスト・返答・トークンは、ログにも例外メッセージにも出さない。メインスレッドで使う
 * (セッションのスレッドからの通知は、すべて [main] に post して戻す)。
 *
 * ## マイクを開く条件(これ以外では開かない)
 * 1. ブリッジが設定済み(`configured`)。未設定ならセッション(とそのスレッド)を作らない。
 * 2. B が**確定**(`onSettled(B)`)してから [ENTRY_DEBOUNCE_MS] 後も、まだ B に落ち着いていて、ドラッグ・アニメーション中でないこと。
 *    ドラッグ中なら落ち着いてからもう一度デバウンスする。その間に A へ戻る・背面・待機・設定画面を開く、のいずれでも開始しない。
 * 3. 入場の理由: スワイプ(利用者の操作)は `daidai_auto_listen` と無関係に開く。通知の自動切替は `DaidaiEntry.openMic`
 *    (ポリシーの歯止め + `daidai_auto_listen` + 設定済み)が true のときだけ。
 *
 * ## 止める条件(どれでも [VoiceRuntime.cancel] + [VoiceRuntime.stopMicNow])
 * B から離れる動き(右スワイプ・無操作での復帰のアニメーションの開始、確定)、B が見えなくなる、`onPause`、待機(時計)、
 * モニター消灯、設定画面を開く、マイクが開いているのに B が見えていないスナップショット。`onResume` は常に A から始まり、セッションは始めない。
 * Intent の extras ではページもマイクも動かせない(この型は Intent を知らない)。
 *
 * ## マイクを止める方法(運用。R16 の文書は T9。設計書 §7)
 * ① 本体のマイクオフスイッチ(`mic_silent` で検出して案内)、② 設定の `daidai_url` を空にする(音声機能が完全に止まる)、
 * ③ `adb shell pm revoke io.github.ysogabe.miharashi.panel android.permission.RECORD_AUDIO`。
 */
class DaidaiVoice(
    private val pages: PageController,
    private val policy: AutoSwitchPolicy,
    private val settings: () -> DaidaiSettings,
    private val factory: VoiceRuntimeFactory,
    /** メインスレッドの `post` / `postDelayed`(デバウンスと、セッションのスレッドからの通知の受け口) */
    private val main: SessionScheduler,
    private val render: (DaidaiUiState) -> Unit,
    /** 開始音。マイクを開いた直後に**セッションのスレッドで**鳴らす(メインが詰まっても保護時間内に収まる)。例外は内側で捕捉する */
    private val startSound: StartSound,
    private val nowMs: () -> Long,
) : DaidaiEntryListener, PageListener {
    private var runtime: VoiceRuntime? = null
    private var runtimeKey: Pair<String, String>? = null
    private var runtimeId = 0

    private var pendingEntry: DaidaiEntry? = null
    private var debounce: Cancelable? = null
    private var waitingForIdle = false

    /** いまのセッションが自動切替の起点か(再試行で false になる) */
    private var sessionAuto = false

    private var baseUi = DaidaiUiState()
    private var snapshot: VoiceSnapshot? = null

    init {
        pages.addListener(this)
    }

    // ---- 画面

    /** [DaidaiController] が組んだ B の表示(通知カード・未設定の案内)。音声の状態を重ねて描く */
    fun onBaseUi(state: DaidaiUiState) {
        baseUi = state
        renderMerged()
    }

    private fun renderMerged() {
        val s = snapshot
        render(
            if (s == null) baseUi
            else baseUi.copy(
                voice = s.state, transcript = s.transcript, reply = s.reply, error = s.error, diagnostics = s.diagnostics,
                micActive = s.micActive, noticePose = s.noticePose ?: baseUi.noticePose,
            ),
        )
    }

    // ---- 入場・退場

    override fun onEntered(entry: DaidaiEntry) {
        cancelPendingStart()
        if (!entry.configured || !entry.openMic) return // 未設定・歯止めでは、セッションもスレッドも作らない
        if (entry.reason != PageReason.SWIPE && entry.reason != PageReason.AUTO_NOTICE) return
        pendingEntry = entry
        armDebounce()
    }

    override fun onLeft() = cancel()

    override fun onMoveRequested(target: Page, reason: PageReason) {
        if (target == Page.A) cancel() // 右スワイプの指を離した・無操作での復帰: 確定を待たずに止める
    }

    override fun onSnap(page: Page) = cancel()

    override fun onBVisibleChanged(visible: Boolean) {
        if (!visible) cancel()
    }

    override fun onIdle() {
        if (!waitingForIdle) return
        waitingForIdle = false
        if (pendingEntry != null && pages.settledPage == Page.B) armDebounce()
    }

    private fun armDebounce() {
        debounce?.cancel()
        debounce = main.postDelayed({
            debounce = null
            fire()
        }, ENTRY_DEBOUNCE_MS)
    }

    private fun fire() {
        val entry = pendingEntry ?: return
        if (pages.settledPage != Page.B) { pendingEntry = null; return }
        if (pages.userBusy) { waitingForIdle = true; return } // ドラッグ中・アニメーション中は開かない。落ち着いてからもう一度
        pendingEntry = null
        startSession(entry)
    }

    private fun startSession(entry: DaidaiEntry) {
        val s = settings()
        if (!s.isConfigured()) return
        val reason = when (entry.reason) {
            PageReason.SWIPE -> StartReason.SWIPE
            PageReason.AUTO_NOTICE -> StartReason.NOTIFICATION
            else -> return
        }
        val rt = ensureRuntime(s) ?: return
        sessionAuto = entry.autoStarted
        rt.cancel() // 念のため(進行中のものは start が無視するので、必ず空にしてから)
        rt.start(reason, entry.noticeId, entry.autoStarted)
    }

    /** ERROR 表示のタップ。**利用者のタップだけ**が呼ぶ(自動の再試行はしない)。B に落ち着いていて、ERROR が出ているときだけ */
    fun onErrorTapped() {
        val rt = runtime ?: return
        if (snapshot?.state != VoiceState.ERROR) return
        if (pages.settledPage != Page.B || pages.userBusy) return
        if (!settings().isConfigured()) return
        sessionAuto = false // 再試行は利用者の操作。自動切替の歯止めには数えない
        rt.retry()
    }

    // ---- 止める

    /** 開始待ちを捨て、セッションを取り消してマイクを止める。冪等。どの離脱でも呼ぶ */
    fun cancel() {
        cancelPendingStart()
        val rt = runtime ?: return
        rt.cancel()
        rt.stopMicNow()
    }

    /** `onResume`: 常に A から始まる。何も開始しない。接続先(URL・トークン)が変わっていたら、古いセッションを捨てる */
    fun onResume() {
        cancel()
        val key = runtimeKey
        if (runtime != null && key != settings().key()) disposeRuntime()
    }

    /** `onPause`・破棄: 取り消してスレッドなどを片づける */
    fun stop() {
        cancel()
        disposeRuntime()
    }

    private fun cancelPendingStart() {
        debounce?.cancel()
        debounce = null
        pendingEntry = null
        waitingForIdle = false
    }

    private fun disposeRuntime() {
        val rt = runtime ?: return
        runtime = null
        runtimeKey = null
        runtimeId++ // 古いセッションの遅れた通知を捨てる
        rt.stopMicNow()
        rt.close()
        snapshot = null
        pages.sessionActive = false
        renderMerged()
    }

    private fun ensureRuntime(s: DaidaiSettings): VoiceRuntime? {
        val key = s.key()
        if (runtime != null && runtimeKey == key) return runtime
        disposeRuntime()
        val id = ++runtimeId
        return try {
            factory.create(s, Listener(id)).also { runtime = it; runtimeKey = key }
        } catch (_: Exception) {
            null // 作れなければマイクは開かない(例外のメッセージは持ち出さない)
        }
    }

    private fun DaidaiSettings.key() = url to token

    // ---- セッションのスレッドからの通知(main へ戻す)

    private inner class Listener(private val id: Int) : VoiceSessionListener {
        override fun onSnapshot(snapshot: VoiceSnapshot) {
            main.post { if (id == runtimeId) onSnapshotOnMain(snapshot) }
        }

        override fun onMicOpened() {
            // 開始音: セッションのスレッドで鳴らす。例外は録音を止める理由にしない(ここで投げるとセッションが終わる)
            try {
                startSound.play()
            } catch (_: Exception) {
            }
            main.post { if (sessionAuto) policy.onAutoMicStarted(nowMs()) }
        }

        override fun onEnded(autoStarted: Boolean, reason: String) {
            // 古い(作り直し前の)セッションの終了も、歯止めの記録としては数える
            main.post { policy.onSessionEnded(autoStarted, reason) }
        }
    }

    private fun onSnapshotOnMain(s: VoiceSnapshot) {
        snapshot = s
        pages.sessionActive = s.active
        renderMerged()
        // マイクが開いているのに B が見えていない: 結線の想定外。確実に止める
        if (s.micActive && !pages.bVisible) cancel()
    }

    companion object {
        /** B の確定からセッション開始までの待ち(設計書 §4.3) */
        const val ENTRY_DEBOUNCE_MS = 300L
    }
}
