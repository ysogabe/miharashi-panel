package io.github.ysogabe.miharashi.panel

import java.util.TimeZone

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

    /**
     * 往復の要求([TurnRequest])で新しいセッションを始める(段階 2a-P)。進行中なら無視される。呼び出したスレッドで開く許可を控えるので、
     * これのあとに [cancel] が来ると作成の要求は出ない。
     */
    fun start(request: TurnRequest)

    /** [retry] の [TurnRequest] 版。ERROR でなければ何もしない */
    fun retry(request: TurnRequest)

    /**
     * 会話の終わりをホストへ伝える(`POST /v1/voice/conversations/end`)。**ノンブロッキング**で、どのスレッドから呼んでもよい(メインスレッドで通信しない)。
     * 最良努力で、失敗は握りつぶす。[close] より前に呼んだものは、[close] のあとでも送られる。ハンドルは不正な形でも例外にしない。
     */
    fun endConversation(handle: String)

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
 *
 * ## 会話の制御(段階 2a-P。設計書(段階 2)§5)
 * 往復(= [VoiceSession] の 1 セッション)の外側に「会話」を持つ。会話の開始のたびに世代([conversationGen])を進め、往復に
 * 「世代 + 往復番号」の [TurnTag] を付ける。往復の終わり([VoiceSessionListener.onEnded])・ハンドルの受け取り([VoiceSessionListener.onConversation])・
 * 残響の待ちの予約は、すべて世代を照合し、今の会話のものだけを反映する(取消のあとに届く古い通知で、新しい会話を動かさない。§5.4)。
 * 会話の終わりは [endConversation] の 1 か所だけ(取り出して消す形。どの理由でも 1 回しか通知しない)で、ハンドルがあれば
 * [VoiceRuntime.endConversation] を 1 回だけ呼ぶ。
 *
 * - 続けるかの判定([decide]): 往復の結果が続けてよい([TurnOutcome.canContinue])、B に落ち着いて見えている、設定済み、
 *   ホストの `continue` が false でない、往復数が [MAX_TURNS] 未満、会話の開始から [ACCEPT_WINDOW_MS] 未満、在席ゲート(S2-U9)。
 * - 続けるなら [echoWaitMs](残響の待ち)の予約を置き、来たら条件(B・受付の期限・設定・在席)をもう一度確かめて [VoiceRuntime.start] する
 *   (在席の規則は [presenceAllows] を [decide] と共有)。ドラッグ・アニメーション中([PageController.userBusy])なら開始を保留し、
 *   [onIdle] で落ち着いてから確かめ直して始める(保留の間も会話は続き、受付の期限で必ず終わる)。
 * - 自動切替の歯止め(R5): [AutoSwitchPolicy] へ渡すのは会話の**最初の往復**だけ。自動起動の会話が上限・期限・`continue=false` で終わったときだけ
 *   [AutoSwitchPolicy.onAutoConversationLimited](S2-U14)。
 * - 会話の間(往復の間の残響の待ち・作成の待ちを含む)は [PageController.sessionActive] を true にして B の無操作タイマーを止める。
 *
 * ## 開始の合図(段 G。設計書(段階 2)§5.8)
 * 会話の**最初の往復**にだけ、合図([StartCuePlan])を付ける(続きの往復は今までどおり別の短い音。S2-U10)。挨拶は往復に数えない。
 * - 挨拶の選び方は [StartCue.choose]。場面は会話の起点([Origin])で決める: スワイプ → `g_*_swipe`、通知の自動切替 → `g_*_notice`、
 *   ERROR のタップでの再試行 → `g_retry`(前の会話の `reason` が `notification` でも)。時間帯の挨拶は、その時間帯で最初の 1 回だけ。
 *   「挨拶済みの時間帯」はメモリにだけ持ち(保存しない)、**挨拶を鳴らし始めたとき**([VoiceSessionListener.onCueStarted])に覚える。
 *   チャイムに戻った・再試行だった、では使わない。
 * - 挨拶の WAV は [CueSource](接続先ごと。`onPause` で runtime を捨てても残す)から取る。無ければ null のまま渡し、セッションがチャイムに戻す。
 *   取り直しは `onResume`・入場・再試行の前に [CueSource.refreshIfDue](待たない)。
 * - 自動切替の歯止め(R5)の [AutoSwitchPolicy.onAutoMicStarted] は、合図の段があるセッションでは**合図を鳴らし始めたとき**に呼ぶ
 *   (途中で取り消しても、端末が自分から喋ったことは数える)。合図の段が無いときだけ、今までどおりマイクを開いたときに呼ぶ。1 セッションに 1 回。
 * - 開始音(ビープ)は、合図の段が無いとき([MicCue.NONE])と、挨拶もチャイムも鳴らせなかったとき([MicCue.FAILED])だけ鳴らす。
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
    /** 在席ゲート(続きの往復ごとに毎回見る。S2-U9)。本番は `DaidaiController.presenceGate` */
    private val presence: (Long) -> GateVerdict,
    /** 残響の待ち(再生の完了からマイクを開くまで)。本番は [ECHO_WAIT_MS]。設定画面・Settings には出さない */
    private val echoWaitMs: Long = ECHO_WAIT_MS,
    /** 挨拶の置き場(接続先ごと)。本番は [CueStore.create]。既定は「挨拶なし」(合図はチャイムになる) */
    private val cueFactory: CueSourceFactory = CueSourceFactory { null },
    /** 端末の時計(エポックのミリ秒。時間帯の挨拶にだけ使う。[nowMs] は単調な時計で、時間帯には使えない) */
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    /** その時刻のタイムゾーンの UTC からのずれ(`TimeZone.getDefault()`。`PanelView.tzOffsetMs` と同じ) */
    private val tzOffsetMs: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() },
) : DaidaiEntryListener, PageListener {
    private var runtime: VoiceRuntime? = null
    private var runtimeListener: Listener? = null
    private var runtimeKey: Pair<String, String>? = null
    private var runtimeId = 0

    /** 挨拶の置き場と、その接続先 */
    private var cues: CueSource? = null
    private var cuesKey: Pair<String, String>? = null

    /** 最後に時間帯の挨拶を鳴らし始めた時間帯の印([StartCue.daypartKey])。メモリだけ(アプリの再起動で消えてよい) */
    private var greetedDaypart: Long? = null

    /** 会話の終わりをホストへ送った(runtime に頼んだ)ハンドル。新しい 16 件だけ持ち、同じハンドルを 2 回頼まない(ホストへの重複は [DeleteOnCloseBridge] も抑える) */
    private val endedHandles = LinkedHashSet<String>()

    private var pendingEntry: DaidaiEntry? = null
    private var debounce: Cancelable? = null
    private var waitingForIdle = false

    private var baseUi = DaidaiUiState()
    private var snapshot: VoiceSnapshot? = null

    /** 会話の世代(単調に増える)。会話の開始と終わりのたびに進める。往復の [TurnTag.conversationGen] と照合する */
    private var conversationGen = 0L

    /** 今の会話。ない(離脱した・終わった)なら null。[endConversation] だけが null に戻す */
    private var conversation: Conversation? = null

    /** 直前の会話の `reason` と `notice_id`(ERROR のタップでの再試行が、新しい会話にそのまま引き継ぐ。会話が終わったあとも持つ) */
    private var lastReason: StartReason? = null
    private var lastNoticeId: String? = null

    /** 会話の起点(診断・再試行・歯止めの分け方に使う) */
    private enum class Origin { SWIPE, AUTO_NOTICE, RETRY }

    /**
     * 会話 1 つ分の状態(メインスレッドだけで触る)。`reason`・`noticeId`・`autoStarted` は最初の値を続きの往復でもそのまま送る(§5.1)。
     * ハンドルは秘密に準ずるので `toString` に出さない。
     */
    private class Conversation(
        val gen: Long,
        val origin: Origin,
        val reason: StartReason,
        val noticeId: String?,
        val autoStarted: Boolean,
        /** 最初の往復の `start` の時刻(受付の期限の起点) */
        val startedAtMs: Long,
    ) {
        var handle: String? = null

        /** ホストが `conversation.continue = false` と返した */
        var hostStop = false

        /** 始めた往復数(最初の往復を含む) */
        var turnsStarted = 0

        /** 進行中(または直前)の往復番号。残響の待ちの間は直前の往復番号 */
        var currentTurn = 0

        /** 終わりの判定を済ませた往復番号(同じ往復の終わりの通知が重複しても判定は 1 回) */
        var decidedTurn = 0

        /** 一度でも「進行中」のスナップショットを見たか(再試行が無視されたことの検出に使う) */
        var sawActive = false

        /** 残響の待ちの予約。続きの開始を保留している間は、受付の期限で会話を終える予約(どちらも [endConversation] が消す) */
        var follow: Cancelable? = null

        /** ドラッグ・アニメーション中で開始を保留している続きの往復番号(0 = 保留なし)。往復数は保留の間に進めない */
        var pendingTurn = 0

        override fun toString() = "Conversation(gen=$gen, turns=$turnsStarted)"
    }

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
                micActive = s.micActive, noticePose = s.noticePose ?: baseUi.noticePose, cuePlaying = s.cuePlaying,
                brain = s.brain, preparing = s.preparing,
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
        resumePendingFollow()
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
        ensureCues(s)?.refreshIfDue() // 失敗のあとなら取り直す(待たない。挨拶はセッションが作成のあとに引くので、作成の往復のあいだに終われば今回から使える)
        endConversation() // 前の会話が残っていれば先に終える(世代を進め、ハンドルがあれば終わりの通知)
        rt.cancel() // 念のため(進行中のものは start が無視するので、必ず空にしてから)
        val origin = if (entry.reason == PageReason.AUTO_NOTICE) Origin.AUTO_NOTICE else Origin.SWIPE
        val c = beginConversation(origin, reason, entry.noticeId, entry.autoStarted)
        rt.start(firstTurnRequest(c))
    }

    /** 新しい会話を始める(世代を進める)。最初の往復の番号を 1 にする */
    private fun beginConversation(origin: Origin, reason: StartReason, noticeId: String?, autoStarted: Boolean): Conversation {
        val c = Conversation(++conversationGen, origin, reason, noticeId, autoStarted, nowMs())
        c.turnsStarted = 1
        c.currentTurn = 1
        conversation = c
        lastReason = reason
        lastNoticeId = noticeId
        updateSessionActive()
        return c
    }

    /** 最初の往復の要求: `conversation: {}`(新しい会話)、通常の無発話の待ち、`follow = false`、開始の合図つき(段 G) */
    private fun firstTurnRequest(c: Conversation) = TurnRequest(
        c.reason, c.noticeId, c.autoStarted, conversation = ConversationParam(null), follow = false, tag = TurnTag(c.gen, 1),
        cue = cuePlan(c.origin),
    )

    /** 会話の起点から合図を選ぶ(段 G)。挨拶の WAV が無ければ `greeting = null`(セッションがチャイムに戻す) */
    private fun cuePlan(origin: Origin): StartCuePlan {
        val scene = when (origin) {
            Origin.SWIPE -> CueScene.SWIPE
            Origin.AUTO_NOTICE -> CueScene.NOTIFICATION
            Origin.RETRY -> CueScene.RETRY
        }
        val now = wallClockMs()
        val offset = try { tzOffsetMs(now) } catch (_: Exception) { 0L }
        val choice = StartCue.choose(scene, now, offset, greetedDaypart)
        val source = cues
        // 挨拶の WAV はセッションが作成のあとに引く(入場の直前に頼んだ取り直しが、作成の往復のあいだに終われば使える)
        return StartCuePlan({ source?.get(choice.name) }, StartChime.wav(), choice)
    }

    /** 接続先の挨拶の置き場(作り直しは接続先が変わったときだけ。未設定なら捨てる) */
    private fun ensureCues(s: DaidaiSettings): CueSource? {
        if (!s.isConfigured()) {
            disposeCues()
            return null
        }
        val key = s.key()
        if (cues != null && cuesKey == key) return cues
        disposeCues()
        cues = try { cueFactory.create(s) } catch (_: Exception) { null }
        cuesKey = key
        return cues
    }

    private fun disposeCues() {
        val c = cues ?: return
        cues = null
        cuesKey = null
        try { c.close() } catch (_: Exception) { }
    }

    /** 挨拶を鳴らし始めた(メインスレッド)。時間帯の挨拶なら、その時間帯を挨拶済みにする */
    private fun onCueStartedOnMain(greeting: CueChoice?) {
        greeting?.daypartKey?.let { greetedDaypart = it }
    }

    /** ERROR 表示のタップ。**利用者のタップだけ**が呼ぶ(自動の再試行はしない)。B に落ち着いていて、ERROR が出ているときだけ */
    fun onErrorTapped() {
        val rt = runtime ?: return
        if (snapshot?.state != VoiceState.ERROR) return
        if (pages.settledPage != Page.B || pages.userBusy) return
        if (!settings().isConfigured()) return
        // 直前のタップの再試行が、まだ始まった(進行中のスナップショットが届いた)ことを確かめられていない間の 2 回目のタップ。
        // 二重に新しい会話を作ると、進行中の往復の印と今の会話の世代が食い違い、会話が終わらなくなる
        conversation?.let { if (it.origin == Origin.RETRY && !it.sawActive) return }
        val reason = lastReason ?: return
        ensureCues(settings())?.refreshIfDue()
        // 再試行は利用者の操作で、**新しい会話**にする(世代を進め、`conversation: {}` から)。autoStarted = false なので、
        // 自動切替の歯止めには数えず、照度が取れなくても続く(S2-U9)。ホストへの reason と notice_id は直前の会話の値のまま
        endConversation()
        val c = beginConversation(Origin.RETRY, reason, lastNoticeId, autoStarted = false)
        rt.retry(firstTurnRequest(c))
    }

    // ---- 止める

    /** 開始待ちを捨て、セッションを取り消してマイクを止める。冪等。どの離脱でも呼ぶ */
    fun cancel() {
        cancelPendingStart()
        endConversation() // 世代を進めて残響の待ちを消し、終わりの通知を積む。**そのあとで** rt.cancel(§5.4)。2 回目以降の呼び出しは何も送らない
        val rt = runtime ?: return
        rt.cancel()
        rt.stopMicNow()
    }

    /** `onResume`: 常に A から始まる。何も開始しない。接続先(URL・トークン)が変わっていたら、古いセッションを捨てる */
    fun onResume() {
        cancel()
        val key = runtimeKey
        if (runtime != null && key != settings().key()) disposeRuntime()
        // 挨拶は最初の入場より前に取っておく(接続先が変わっていれば作り直す。未設定なら捨てる)
        ensureCues(settings())?.refreshIfDue()
    }

    /** `onPause`・破棄: 取り消してスレッドなどを片づける */
    fun stop() {
        cancel()
        disposeRuntime()
    }

    /** `onDestroy`: [stop] に加えて、挨拶の置き場も閉じる(取得の途中でも、以後は取りに行かない) */
    fun destroy() {
        stop()
        disposeCues()
    }

    private fun cancelPendingStart() {
        debounce?.cancel()
        debounce = null
        pendingEntry = null
        waitingForIdle = false
    }

    private fun disposeRuntime() {
        val rt = runtime ?: return
        endConversation() // 会話の終わりの通知を close より前に積む(executor の shutdown の前)
        // 201 を受け取ったがメインへの通知がまだ処理されていないハンドル(作り直し・背面の直前の競合)も、close より前に終わらせる
        runtimeListener?.seenHandles()?.forEach { sendEnd(rt, it) }
        runtimeListener = null
        runtime = null
        runtimeKey = null
        runtimeId++ // 古いセッションの遅れた通知を捨てる
        rt.stopMicNow()
        rt.close()
        snapshot = null
        updateSessionActive()
        renderMerged()
    }

    private fun ensureRuntime(s: DaidaiSettings): VoiceRuntime? {
        val key = s.key()
        if (runtime != null && runtimeKey == key) return runtime
        disposeRuntime()
        val id = ++runtimeId
        val listener = Listener(id)
        return try {
            factory.create(s, listener).also { runtime = it; runtimeListener = listener; runtimeKey = key }
        } catch (_: Exception) {
            null // 作れなければマイクは開かない(例外のメッセージは持ち出さない)
        }
    }

    private fun DaidaiSettings.key() = url to token

    // ---- 会話の制御(メインスレッド。設計書(段階 2)§5)

    /**
     * 会話を終える**唯一の入口**(取り出して消す形。2 回目以降・会話が無いときは何もしない)。残響の待ちの予約を消し、世代を進め、
     * ハンドルを受け取っていれば [VoiceRuntime.endConversation] を 1 回だけ呼ぶ。`limited`: 上限・受付の期限・ホストの `continue=false`
     * で終わった(自動起動の会話なら [AutoSwitchPolicy.onAutoConversationLimited]。S2-U14)。
     * 呼び出しは [VoiceRuntime.cancel] より前(§5.4)。[disposeRuntime] は [VoiceRuntime.close] より前に呼ぶ。
     */
    private fun endConversation(limited: Boolean = false) {
        val c = conversation ?: return
        conversation = null
        c.follow?.cancel()
        c.follow = null
        conversationGen++
        c.handle?.let { sendEnd(runtime, it) }
        if (limited && c.autoStarted) policy.onAutoConversationLimited()
        updateSessionActive()
    }

    /** ハンドルの会話の終わりを runtime に頼む(同じハンドルは 1 回だけ)。ノンブロッキング */
    private fun sendEnd(rt: VoiceRuntime?, handle: String) {
        if (rt == null || !endedHandles.add(handle)) return
        if (endedHandles.size > ENDED_HANDLES_MAX) endedHandles.remove(endedHandles.first())
        rt.endConversation(handle)
    }

    /** B の無操作タイマーを止める条件: セッションが進行中、または会話が続いている(往復の間の待ちも含む) */
    private fun updateSessionActive() {
        pages.sessionActive = snapshot?.active == true || conversation != null
    }

    /** 作成の応答の `conversation`(現在の世代のものだけ反映。古い世代のものは、そのハンドルの終わりの通知だけ送る) */
    private fun onConversationOnMain(tag: TurnTag, info: ConversationInfo) {
        val c = conversation
        if (c == null || c.gen != tag.conversationGen) {
            // 取消のあとに届いた古い世代の 201。今の会話のハンドルは上書きせず、そのハンドルだけ終わらせる(重複は DeleteOnCloseBridge が抑える)
            val h = info.handle ?: return
            if (c?.handle != h) sendEnd(runtime, h)
            return
        }
        val h = info.handle
        if (h != null) {
            val old = c.handle
            if (old != null && old != h) sendEnd(runtime, old) // ホストが別のハンドルを返した: 新しい方を使い、古い方は終わらせる
            c.handle = h
        }
        if (!info.continueAllowed) c.hostStop = true
    }

    private enum class Verdict { CONTINUE, END, END_LIMITED }

    /** 往復の終わり(今の runtime の通知だけ)。今の会話の今の往復のものだけ、1 回だけ判定する */
    private fun onTurnEndedOnMain(tag: TurnTag, outcome: TurnOutcome) {
        val c = conversation ?: return
        if (tag.conversationGen != c.gen || tag.turn != c.currentTurn || tag.turn <= c.decidedTurn) return
        c.decidedTurn = tag.turn
        when (decide(c, outcome)) {
            Verdict.CONTINUE -> {
                val gen = c.gen
                val next = c.currentTurn + 1
                c.follow = main.postDelayed({ onFollowDue(gen, next) }, echoWaitMs)
            }
            Verdict.END -> endConversation()
            Verdict.END_LIMITED -> endConversation(limited = true)
        }
    }

    /** 続けるかの判定(設計書 §5.2 の 1〜5 と S2-U9)。上限・期限・`continue=false` で終わるときだけ [Verdict.END_LIMITED] */
    private fun decide(c: Conversation, outcome: TurnOutcome): Verdict {
        if (!outcome.canContinue) return Verdict.END
        if (pages.settledPage != Page.B || !pages.bVisible) return Verdict.END
        if (!settings().isConfigured()) return Verdict.END
        if (c.hostStop || c.turnsStarted >= MAX_TURNS || nowMs() >= c.startedAtMs + ACCEPT_WINDOW_MS) return Verdict.END_LIMITED
        return if (presenceAllows(c)) Verdict.CONTINUE else Verdict.END
    }

    /**
     * 在席ゲート(S2-U9)の判定。[decide](往復の終わり)と [startFollowTurn](続きを始める直前)で**同じ規則**を使う。
     * 在席なら true、不在なら false。照度が取れないとき(UNKNOWN)は、通知の自動起動で始まった会話は false、スワイプ・再試行の会話は true。
     * 在席による終わりは、上限・期限・`continue=false` ではないので S2-U14 には数えない。
     */
    private fun presenceAllows(c: Conversation): Boolean = when (presence(nowMs()).presence) {
        Presence.PRESENT -> true
        Presence.ABSENT -> false
        Presence.UNKNOWN -> !c.autoStarted
    }

    /**
     * 残響の待ちが明けた。世代を照合し、ドラッグ・アニメーション中なら開始を保留して [onIdle] を待つ(最初の往復の [fire] と同じ条件)。
     * そうでなければ [startFollowTurn]。
     */
    private fun onFollowDue(gen: Long, turn: Int) {
        val c = conversation ?: return
        if (c.gen != gen || c.currentTurn + 1 != turn) return
        c.follow = null
        if (pages.userBusy && pages.settledPage == Page.B) {
            // 保留: 会話は続いている(sessionActive は true のまま)。往復数は進めない。落ち着かないまま受付の期限が来ても必ず終える
            c.pendingTurn = turn
            val remaining = maxOf(0L, c.startedAtMs + ACCEPT_WINDOW_MS - nowMs())
            c.follow = main.postDelayed({ onPendingDeadline(gen, turn) }, remaining)
            return
        }
        startFollowTurn(c, turn)
    }

    /** 保留したまま受付の期限が来た。落ち着くのを待たずに会話を終える(マイクは開いていない) */
    private fun onPendingDeadline(gen: Long, turn: Int) {
        val c = conversation ?: return
        if (c.gen != gen || c.pendingTurn != turn) return
        c.follow = null
        c.pendingTurn = 0
        endConversation(limited = true)
    }

    /** 落ち着いた([PageListener.onIdle])。保留した続きがあれば、条件を確かめ直して始める(A に落ち着いたなら会話を終える) */
    private fun resumePendingFollow() {
        val c = conversation ?: return
        val turn = c.pendingTurn
        if (turn == 0) return
        if (pages.userBusy) return
        c.follow?.cancel()
        c.follow = null
        c.pendingTurn = 0
        startFollowTurn(c, turn)
    }

    /**
     * 続きの往復を始める直前の確かめ直し(世代は呼び出し側で照合済み): B に落ち着いている、受付の期限の前、設定済み、在席([presenceAllows])。
     * どれかが外れたら会話を終える(期限だけが [endConversation] の `limited`。在席・B・設定は S2-U14 に数えない)。
     */
    private fun startFollowTurn(c: Conversation, turn: Int) {
        if (pages.settledPage != Page.B || !pages.bVisible) return endConversation()
        if (nowMs() >= c.startedAtMs + ACCEPT_WINDOW_MS) return endConversation(limited = true)
        val rt = runtime
        if (rt == null || !settings().isConfigured()) return endConversation()
        if (!presenceAllows(c)) return endConversation()
        c.turnsStarted = turn
        c.currentTurn = turn
        // ハンドルが無ければ(旧ホスト)`{}` で送る。ホストは無視する
        rt.start(
            TurnRequest(
                c.reason, c.noticeId, c.autoStarted, conversation = ConversationParam(c.handle),
                noSpeechS = VoiceLimits.FOLLOW_NO_SPEECH_S, follow = true, tag = TurnTag(c.gen, turn),
            ),
        )
    }

    // ---- セッションのスレッドからの通知(main へ戻す)

    private inner class Listener(private val id: Int) : VoiceSessionListener {
        /** この runtime で 201 から受け取ったハンドル(セッションのスレッドで足し、メインで読む)。新しい 16 件 */
        private val seen = ArrayDeque<String>()

        fun seenHandles(): List<String> = synchronized(seen) { seen.toList() }

        override fun onSnapshot(snapshot: VoiceSnapshot) {
            main.post { if (id == runtimeId) onSnapshotOnMain(snapshot) }
        }

        override fun onMicOpened(autoStarted: Boolean, follow: Boolean, tag: TurnTag) = onMicOpened(autoStarted, follow, tag, MicCue.NONE)

        override fun onCueStarted(autoStarted: Boolean, tag: TurnTag, greeting: CueChoice?) {
            val startedAtMs = nowMs()
            main.post { onCueStartedOnMain(greeting) }
            // 歯止め(R5): 合図の段があるセッションでは、マイクより前の「端末が喋り始めた」時点で数える(途中の取消でも数える)。
            // 合図は最初の往復にだけ付くが、念のため続きの往復の印(turn > 1)では数えない
            if (autoStarted && tag.turn <= 1) main.post { policy.onAutoMicStarted(startedAtMs) }
        }

        override fun onMicOpened(autoStarted: Boolean, follow: Boolean, tag: TurnTag, cue: MicCue) {
            val openedAtMs = nowMs() // 開いた時刻。開始音(と main への配送)の遅れを含めない
            // 開始音: セッションのスレッドで鳴らす。例外は録音を止める理由にしない(ここで投げるとセッションが終わる)。
            // 続きの往復は、最初の往復と区別できる別の音(S2-U10)。最初の往復は、合図(挨拶・チャイム)を鳴らし終えていれば鳴らさない
            try {
                if (follow) startSound.playFollow() else if (cue != MicCue.HEARD) startSound.play()
            } catch (_: Exception) {
            }
            // 実際にマイクを開いたセッションの値(autoStarted・follow)と時刻を、いまここで取り出して不変の値として投稿する。
            // main で可変のフィールドを読まない(手動のセッションへ切り替わっても、取り違えない)。
            // 作り直し前(破棄済み)の runtime で実際に開いたマイクも、歯止めの記録としては数える(画面への反映だけが現行の世代に限られる)。
            // 歯止め(R5)に渡すのは会話の最初の往復だけ: 続きの往復(follow)のマイクはクールダウン・1 時間 6 回に数えない
            // 合図の段があったセッション(HEARD・FAILED)は、合図を鳴らし始めたときに数え済み
            if (autoStarted && !follow && cue == MicCue.NONE) {
                main.post { policy.onAutoMicStarted(openedAtMs) }
            }
        }

        override fun onConversation(tag: TurnTag, info: ConversationInfo) {
            info.handle?.let { h ->
                synchronized(seen) {
                    seen.addLast(h)
                    if (seen.size > ENDED_HANDLES_MAX) seen.removeFirst()
                }
            }
            // 作り直し前の runtime のものは、(閉じる前後の回収は DeleteOnCloseBridge が行うので)何もしない
            main.post { if (id == runtimeId) onConversationOnMain(tag, info) }
        }

        override fun onEnded(autoStarted: Boolean, reason: String, outcome: TurnOutcome, tag: TurnTag) {
            main.post {
                // 古い(作り直し前の)セッションの終了も、歯止めの記録としては数える。ただし渡すのは会話の最初の往復だけ(R5)
                if (tag.turn <= 1) policy.onSessionEnded(autoStarted, reason)
                if (id == runtimeId) onTurnEndedOnMain(tag, outcome)
            }
        }
    }

    private fun onSnapshotOnMain(s: VoiceSnapshot) {
        snapshot = s
        val c = conversation
        if (c != null) {
            if (s.active) c.sawActive = true
            // 再試行が無視された(ERROR の表示が先に終わっていた)とき、会話だけが残って B の無操作タイマーを止め続けないように終える
            else if (c.origin == Origin.RETRY && !c.sawActive && s.state != VoiceState.ERROR) endConversation()
        }
        updateSessionActive()
        renderMerged()
        // マイクが開いているのに B が見えていない: 結線の想定外。確実に止める
        if (s.micActive && !pages.bVisible) cancel()
    }

    companion object {
        /** B の確定からセッション開始までの待ち(設計書 §4.3) */
        const val ENTRY_DEBOUNCE_MS = 300L

        /** 1 会話の往復数の上限(S2-U2) */
        const val MAX_TURNS = 5

        /** 受付の期限(S2-U2): 会話の開始(最初の往復の `start`)からこの時間を過ぎたら新しい往復を始めない。進行中の往復は切らない */
        const val ACCEPT_WINDOW_MS = 5 * 60_000L

        /** 残響の待ち: 再生の完了(往復の終わりの通知)から続きのマイクを開く(`start`)までの待ち。開いた直後の録音の破棄([VoiceLimits.START_TONE_GUARD_MS])とは別のもの */
        const val ECHO_WAIT_MS = 300L

        private const val ENDED_HANDLES_MAX = 16
    }
}
