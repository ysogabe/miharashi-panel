package io.github.ysogabe.miharashi.panel

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** [VoiceSession] の内部状態(設計書 §5.5)。並びは前進の順序(`ERROR` は順序の外)。表示用の [VoiceState] へは [VoiceSession] が変換する。 */
enum class VoicePhase {
    IDLE, PREPARING, LISTENING, HEARING, WAITING, REPLYING, DONE, ERROR;

    /** 進行中(セッションが生きている) */
    val isActive: Boolean get() = this == PREPARING || this == LISTENING || this == HEARING || this == WAITING || this == REPLYING

    /** マイクを開いている間の状態 */
    val isMicPhase: Boolean get() = this == LISTENING || this == HEARING
}

/**
 * 音声セッションの状態機械(設計書 §5.2, §5.4, §5.5, §5.8, §7)。純 Kotlin で、時刻・スレッド・タイマー・通信・マイク・再生はすべて注入する。
 *
 * ## スレッド
 * 公開メソッド([start] / [cancel] / [retry])はどのスレッドから呼んでもよく、[SessionScheduler] に post して処理する。
 * マイクの PCM・通信の結果・SSE のイベント・タイマー・再生の完了は、すべて同じ [SessionScheduler] に戻して**直列に**処理する
 * (内部の状態は、そのスレッドでしか触らない)。ブロッキングの通信は [SessionWorker] で走らせる。
 *
 * ## 世代番号
 * セッションを始めるたび・終えるたびに世代を進める。ワーカーの結果・タイマー・マイクのコールバックは作成時の世代を持ち、
 * 現在の世代でなければ捨てる(A↔B の往復や、作成中の離脱で遅れて届く結果への防御)。**例外は作成の成功**: 古い世代の作成が
 * 成功して届いたら、録音を始めず、直ちにその `session_id` で `DELETE` する(S7)。
 *
 * ## マイクを開く許可(スレッドをまたぐ)
 * 世代番号はセッションのスレッドでしか進まないので、キューに積まれた作成成功の処理(`onCreated → openMic`)より後に処理される取消では、
 * マイクを開くのを止められない。そこで [cancel] と [stopMicNow](runtime の close も)は、**呼び出しスレッドから同期に**許可の番号を進め、
 * 許可は [java.util.concurrent.atomic.AtomicInteger] で、**取消側はロックを取らずに番号を進めるだけ**(続けて `audio.stop()`)。取消側([cancel]・[stopMicNow]・runtime の close)は
 * メインスレッドから呼ばれるので、`AudioSource.start`(`AudioRecord` の作成・`startRecording()`)の完了を**待たない**。
 * [openMic] は ① 開く前に許可を照合し、② `audio.start` を**ロックの外**で、許可の確認([AudioSource.start] の `isCurrent`)つきで呼び、③ 戻ったあとにもう一度許可を照合して、
 * 無効になっていれば(開始の最中に取消された)`audio.stop()` を含む取消の終了([finish])に進む。取消が「開始の前・最中・後」のどこに入っても、
 * 取消の後に新たに録音を始めたまま残ることはない: 前なら ① か `isCurrent` が弾き、最中なら `AudioSource` が公開せずに止めるか ③ が止め、後なら取消側の `audio.stop()` が止める。
 * 許可は **[start] / [retry] を呼んだスレッドで同期に**控え、[doStart] に渡す(キューに積まれたあとの値ではない)。[doStart] は、控えた値が現在と違えば
 * (呼び出しのあとに [cancel] / [stopMicNow] / close が来た)何もしない(作成 0 回・状態も変えない)。違わなければそれを `openPermit` にする。
 * したがって `start → cancel` の順は作成 0 回、`cancel → start` の順は今までどおり作成 1 回。
 * 作成をワーカーに積んだあと、ワーカーが実際に呼ぶまでの間に来た取消も、ワーカーの呼び出しの**直前**の同じ照合で止める(作成の要求を送らず、結果は黙って捨てる)。
 * 照合を通ったあとに来た取消だけが小さな窓として残り、それは `abortInFlight` の世代と、遅れた 201 の後始末(S7)で回収する。
 *
 * ## 往復(段階 2a-P)
 * 1 つの [VoiceSession] は 1 往復(= 1 セッション)を受け持ち、続きの往復は呼ぶ側が新しい [start] で作る(状態は後退しない。DONE から LISTENING に戻さない)。
 * 往復の要求は [TurnRequest]、結果は [TurnOutcome]([VoiceSessionListener.onEnded] に、呼ぶ側が付けた [TurnTag] と一緒に渡す)。結果は、終わった経路から
 * 明示して決める([finish] の引数。理由の文字列から推測しない)。作成の応答に `conversation` があれば、現在の世代の作成に限り [VoiceSessionListener.onConversation]
 * にそのまま渡す(ハンドルは持たない)。古い世代の作成が成功して届いたときは、`DELETE` に加えて会話の終わり([BridgeApi.endConversation])も最良努力で送る。
 * 続きの往復([TurnRequest.follow])の無発話は ERROR にせず、取消と同じ IDLE に終える(`reason` は "no_speech"、結果は [TurnOutcome.ERROR])。
 *
 * ## 停止の順序(どの状態からでも同じ。[finish])
 * 世代を進める → マイクを止めて release → `abortInFlight()` → 最良努力で `cancel(id)` → 再生停止 → 画面に反映 → `onEnded`。
 * 各段は例外を握りつぶして次へ進む。**マイクの停止は通信の完了に依存しない。**
 * DONE・ERROR・取消のどれで終わるときも同じ手順で、ホストにはセッションの削除(`DELETE`)を送る(認識テキストを早く手放させる)。
 *
 * ## 録音中の必須の通知
 * マイクを開いている間([VoiceSessionListener.onMicOpened]、マイク使用中を含むスナップショットの [VoiceSessionListener.onSnapshot])に
 * リスナーが例外を投げたら、録音を続けず `mic_unavailable` で終わる(開始音もマイク表示もないまま音声を送らない)。
 * 終了処理中の通知(ERROR・DONE・取消のあとのスナップショットと `onEnded`)の例外は握りつぶし、後ろの停止を進める。
 *
 * ## 状態は後退しない
 * `PREPARING < LISTENING < HEARING < WAITING < REPLYING < DONE`。POST の ack(`speech` / `ended`)と SSE(`state` / `reply`)のどちらから
 * 来た情報でも、現在より後ろへの遷移だけを受け入れる。`ended`(ack)・`state=waiting`・`reply`・終端 `error` は録音を止める確定信号で、
 * 終了後に届いた ack・イベントは無視する(S4, S5)。
 *
 * ## 開始音とマイク
 * セッション作成が成功してから(作成前には録音しない。§6)マイクを開き、[VoiceSessionListener.onMicOpened] を呼ぶ。T8 はそこで開始音を鳴らす。
 * 開いてから [startToneGuardMs] の間に届いた録音は捨てる(送らない・無音判定にも使わない)。`mic_silent` の判定窓と 45 秒の上限は、
 * 前者は保護時間の後から、後者はマイクを開いた時刻から数える。
 *
 * ## 開始の合図(段 G。設計書(段階 2)§5.8.4)
 * [TurnRequest.cue] があれば(会話の最初の往復だけ。続きの往復では無視する)、作成が成功して `startSse` したあと、**マイクを閉じたまま**
 * 挨拶を [player] で鳴らす(状態は PREPARING のまま。スナップショットの `cuePlaying` を立てる)。鳴り終えたら [cueEchoWaitMs] 待ってマイクを開き、
 * 開いた直後の [cueGuardMs] の録音を捨てる(開始音は鳴らさない)。挨拶が無い・検査に落ちた・`onDone(false)`・期限(実データの長さ + 1.5 秒)切れなら、
 * 同じ順序でチャイムを鳴らす。チャイムも鳴らせなければ、今までどおりマイクを開いた直後に開始音を鳴らし [startToneGuardMs] を捨てる
 * (自動起動で合図が 0 回になる経路を作らない)。合図の段の結果は [VoiceSessionListener.onMicOpened] の `cue` で渡す。
 * 再生の完了は世代と再生ごとの番号で照合する(期限で止めた挨拶の遅れた `onDone(false)` を、チャイムの結果と取り違えない)。
 * 合図の最中の取消は、[finish] が再生を止める(マイクは一度も開かない)。挨拶は往復に数えない(1 セッションの中の、マイクの前の段)。
 *
 * ## ログ
 * Android に依存しない(`Log` を使わない)。認識テキスト・返答・トークンは `toString` にも例外メッセージにも入れない。
 */
class VoiceSession(
    private val audio: AudioSource,
    private val bridge: BridgeApi,
    private val player: ReplyPlayer,
    private val clock: Clock,
    private val scheduler: SessionScheduler,
    private val worker: SessionWorker,
    private val listener: VoiceSessionListener,
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val appVersion: String = "",
    private val startToneGuardMs: Long = VoiceLimits.START_TONE_GUARD_MS,
    /** セッションの総時間(テスト用に差し替えられる。本番は [VoiceLimits.SESSION_MAX_MS]) */
    private val sessionMaxMs: Long = VoiceLimits.SESSION_MAX_MS,
    private val sseBackoff: () -> Backoff = { Backoff(500, 4_000) },
    /** 合図のあと、マイクを開いた直後に捨てる長さ(段 G) */
    private val cueGuardMs: Long = VoiceLimits.CUE_GUARD_MS,
    /** 合図の再生の完了からマイクを開くまでの残響の待ち(段 G) */
    private val cueEchoWaitMs: Long = VoiceLimits.CUE_ECHO_WAIT_MS,
) {
    private enum class Timer { TOTAL, CREATE, CHUNK, MIC_CAP, MIC_SILENT, NO_SPEECH, REPLY, REPLY_AUDIO, PLAYBACK, SSE_RETRY, ERROR_DISPLAY, CUE, CUE_GAP }

    private class Chunk(val seq: Int, val pcm: ByteArray, val final: Boolean) {
        var attempt = 0
        var token = 0
    }

    // ---- セッション共通(セッションのスレッドでだけ触る)
    private var gen = 0

    /** [doStart] の時点で控えた「マイクを開いてよい」許可([micPermit])。作成が成功したあとの [openMic] で照合する */
    private var openPermit = 0
    private var _phase = VoicePhase.IDLE
    private var sessionId: String? = null
    private var autoStarted = false
    private var startReason = StartReason.SWIPE
    private var noticeId: String? = null
    private var follow = false
    private var tag = TurnTag.NONE
    private var limits = EffectiveLimits.DEFAULTS
    private var transcript = ""
    private var reply = ""
    private var error: VoiceErrorCode? = null
    private var diagnostics: String? = null
    private var noticePose: String? = null
    private var lastSnapshot: VoiceSnapshot? = null

    // ---- 開始の合図(段 G)
    private var cuePlan: StartCuePlan? = null
    private var cueResult = MicCue.NONE
    private var cuePlaying = false
    private var cueIsGreeting = false
    private var cueToken = 0

    /** このセッションでマイクを開いた直後に捨てる長さ(合図を鳴らし終えたら [cueGuardMs]、それ以外は [startToneGuardMs]) */
    private var guardMs = startToneGuardMs
    private val timers = HashMap<Timer, Cancelable>()
    private val timerTokens = HashMap<Timer, Int>()
    private var timerCounter = 0
    private var requestCounter = 0

    // ---- マイクとチャンク
    private var micOpen = false
    private var micOpenedAtMs = 0L
    private var heardSound = false
    private var carry: Int = -1
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var sending = false
    private var flushing = false
    private var nextSeq = 0
    private var highestAccepted = -1
    private var inFlight: Chunk? = null
    private var firstAckSeen = false

    // ---- SSE と返答
    private var lastSeq = -1
    private var sseConn = 0
    private var sseRunning = false
    private var sseReconnects = 0
    private var sseFreeReconnect = false
    private var sseDone = false
    private var backoff = sseBackoff()
    private var replyHandled = false
    private var replyAudio: ReplyAudioInfo? = null
    private var fetchToken = 0

    // ---- スレッドをまたぐ「マイクを開く許可」。取消・close・stopMicNow が、呼び出しスレッドからロックなしで進める。
    // 作成の成功の処理がセッションのキューに積まれたまま取消が来ても、その処理は許可の照合で弾かれる(マイクは開かない)。
    private val micPermit = AtomicInteger()

    /** 現在の内部状態。**セッションのスレッドで読む**(テスト用の公開) */
    val phase: VoicePhase get() = _phase

    // =========================================================================================
    // 公開 API(どのスレッドからでも)

    /**
     * B の確定で呼ぶ。進行中(PREPARING〜REPLYING)のときは**無視**する(同時に 1 セッション。呼び側は先に [cancel] する)。
     * IDLE・DONE・ERROR からは新しいセッションを始める。`noticeId` が `^[A-Za-z0-9_.:-]{1,64}$` に合わなければ送らない。
     */
    fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) = start(TurnRequest(reason, noticeId, autoStarted))

    /**
     * 往復を始める([start] の [TurnRequest] 版)。**呼び出したスレッドで同期に**マイクを開く許可を控える。控えたあとに [cancel] / [stopMicNow] / close が来たら、
     * キューに積まれた開始は何もしない(作成の要求も出さない)。
     */
    fun start(request: TurnRequest) {
        val permit = micPermit.get()
        scheduler.post { doStart(request, permit) }
    }

    /** B を離れる・背面・待機・設定画面。どの状態からでもマイクを止めて release し、IDLE に戻す。冪等。 */
    fun cancel() {
        revokeMicPermit() // 呼び出しスレッドで同期に。キューに積まれた作成成功の処理(onCreated → openMic)にマイクを開かせない
        scheduler.post { doCancel() }
    }

    /**
     * セッションのスレッドを介さずにマイクを止める(最後の防衛線)。冪等。**開く許可を先に取り消してから**止めるので、
     * まだ録音を始めていない作成成功の処理が、止めたあとでマイクを開くことはない。ホストへは何も送らない([cancel] と併せて使う)。
     */
    fun stopMicNow() {
        revokeMicPermit()
        safe { audio.stop() }
    }

    /** ロックを取らない(`audio.start` の完了を待たない)。メインスレッドから呼ぶ */
    private fun revokeMicPermit() {
        micPermit.incrementAndGet()
    }

    /** ERROR 表示のタップ。直前と同じ起点で新しいセッションを始める(利用者の操作なので `autoStarted = false`)。ERROR 以外では何もしない。 */
    fun retry() {
        val permit = micPermit.get()
        scheduler.post {
            if (_phase == VoicePhase.ERROR) doStart(TurnRequest(startReason, noticeId, autoStarted = false), permit)
        }
    }

    /** ERROR のときだけ [request] で新しい往復を始める(それ以外では何もしない)。許可は [start] と同じく呼び出しスレッドで控える。 */
    fun retry(request: TurnRequest) {
        val permit = micPermit.get()
        scheduler.post {
            if (_phase == VoicePhase.ERROR) doStart(request, permit)
        }
    }

    // =========================================================================================
    // 開始と終了

    private fun doStart(request: TurnRequest, permit: Int) {
        if (_phase.isActive) return
        // 呼び出しのあとに取消(cancel / stopMicNow / close)が来ていた。何もしない(世代も状態も表示のタイマーも変えない)
        if (micPermit.get() != permit) return
        resetForNewSession()
        val reason = request.reason
        startReason = reason
        noticeId = request.noticeId?.takeIf { VoiceLimits.NOTICE_ID_REGEX.matches(it) }
        autoStarted = request.autoStarted
        follow = request.follow
        tag = request.tag
        cuePlan = if (request.follow) null else request.cue
        openPermit = permit // これ以後の cancel / stopMicNow / close で、この許可は無効になる
        _phase = VoicePhase.PREPARING
        val g = gen
        arm(Timer.TOTAL, sessionMaxMs) { onTotalTimeout() }
        arm(Timer.CREATE, VoiceLimits.CREATE_TIMEOUT_MS) { fail(VoiceErrorCode.HOST_UNREACHABLE, "create timeout") }
        emit()
        val req = SessionRequest(clientId, appVersion, reason, noticeId, noSpeechS = request.noSpeechS, conversation = request.conversation)
        // ワーカーで実際に呼ぶ直前に、控えた許可を照合する(積んだあとに来た取消は、作成の要求を送る前に止める)。
        // 通信の層(BridgeClient)にも同じ照合を渡す: 接続の登録(中断の世代を控えるのと同じロックの中)・通信の開始・本文を書く直前で見る。
        // 取消は先に許可を進めてから abortInFlight するので、「世代を取り直されて中断が効かない」窓は無い。
        // 最後の照合を通ったあと本文を書き始めるまでの一瞬だけ残り、それは finish の abortInFlight と、遅れて届く 201 の後始末(S7: DELETE と会話の終わり)が回収する
        runBlocking({
            if (micPermit.get() != permit) throw CreateCancelledException()
            // 通信の層でも、接続の登録・通信の開始・本文の送信の直前に同じ照合をする(ここで通った後に来た取消も、要求を送る前に止める)
            bridge.createSession(req) { micPermit.get() == permit }
        }) { r -> onCreated(g, r) }
    }

    private fun doCancel() {
        if (_phase.isActive) {
            cancelledToIdle()
            return
        }
        // 進行中でなくても、マイクと再生は確実に止める(防御)。ホストへは何も送らない
        gen++
        cancelAllTimers()
        safe { audio.stop() }
        safe { player.stop() }
        micOpen = false
        clearOutputs()
        _phase = VoicePhase.IDLE
        emit()
    }

    private fun resetForNewSession() {
        gen++
        cancelAllTimers()
        sessionId = null
        limits = EffectiveLimits.DEFAULTS
        clearOutputs()
        micOpen = false
        micOpenedAtMs = 0
        heardSound = false
        carry = -1
        queue.clear()
        queuedBytes = 0
        sending = false
        flushing = false
        nextSeq = 0
        highestAccepted = -1
        inFlight = null
        firstAckSeen = false
        lastSeq = -1
        sseRunning = false
        sseReconnects = 0
        sseFreeReconnect = false
        sseDone = false
        backoff = sseBackoff()
        replyHandled = false
        replyAudio = null
        fetchToken = 0
        cuePlan = null
        cueResult = MicCue.NONE
        cuePlaying = false
        cueIsGreeting = false
        guardMs = startToneGuardMs
    }

    private fun clearOutputs() {
        transcript = ""
        reply = ""
        error = null
        diagnostics = null
        noticePose = null
    }

    /**
     * 終わらせる唯一の入口(停止の順序)。`next` は DONE・ERROR・IDLE(取消)。
     * IDLE のときは本文を消す(B を離れたら認識結果・返答を残さない)。`onEnded` は進行中だったセッションにつき 1 回。
     */
    private fun finish(next: VoicePhase, err: VoiceErrorCode?, diag: String?, reason: String, outcome: TurnOutcome) {
        val wasActive = _phase.isActive
        val id = sessionId
        gen++ // 1. 古い結果・タイマーを無効にする
        cancelAllTimers()
        safe { audio.stop() } // 2. マイクを止めて release(通信の完了を待たない)
        micOpen = false
        safe { bridge.abortInFlight() } // 3. 通信中の接続を切る
        if (id != null) { // 4. 最良努力でホストのセッションを消す
            runBlocking({ bridge.cancel(id) }) { }
        }
        safe { player.stop() } // 5. 再生を止める(合図の最中なら合図も)
        cuePlaying = false
        cueToken++
        queue.clear()
        queuedBytes = 0
        carry = -1
        inFlight = null
        sending = false
        flushing = false
        sseRunning = false
        if (next == VoicePhase.IDLE) clearOutputs()
        error = err
        diagnostics = diag
        _phase = next
        if (next == VoicePhase.ERROR) arm(Timer.ERROR_DISPLAY, VoiceLimits.ERROR_DISPLAY_MS) { errorToIdle() }
        emit() // 6. 画面へ
        if (wasActive) safe { listener.onEnded(autoStarted, reason, outcome, tag) }
    }

    /**
     * ERROR で終える。**続きの往復([follow])の無発話だけは ERROR にせず**、取消と同じ IDLE に終える(ERROR の表示のタイマーも張らない。`reason` は "no_speech"、
     * 結果は [TurnOutcome.ERROR])。最初の往復の無発話と、`mic_silent` などほかのエラーは続きでも今までどおり ERROR。
     */
    private fun fail(code: VoiceErrorCode, diag: String? = null) {
        if (follow && code == VoiceErrorCode.NO_SPEECH) {
            finish(VoicePhase.IDLE, null, null, code.code, TurnOutcome.ERROR)
        } else {
            finish(VoicePhase.ERROR, code, diag, code.code, TurnOutcome.ERROR)
        }
    }

    private fun finishDone(err: VoiceErrorCode?, diag: String?, outcome: TurnOutcome) =
        finish(VoicePhase.DONE, err, diag, err?.code ?: "done", outcome)

    private fun cancelledToIdle() = finish(VoicePhase.IDLE, null, null, "cancelled", TurnOutcome.CANCELLED)

    private fun errorToIdle() {
        if (_phase != VoicePhase.ERROR) return
        clearOutputs()
        _phase = VoicePhase.IDLE
        emit()
    }

    private fun onTotalTimeout() {
        // 返答が手元にあるなら成功として閉じる。それ以外は取消して timeout
        if (_phase == VoicePhase.REPLYING) finishDone(null, "session time limit", TurnOutcome.SESSION_TIMEOUT) else fail(VoiceErrorCode.TIMEOUT, "session time limit")
    }

    // =========================================================================================
    // 作成とマイク

    private fun onCreated(g: Int, r: Result<SessionInfo>) {
        if (r.exceptionOrNull() is CreateCancelledException) {
            // 作成の要求を出す前に取消が来ていた。エラーにしない。まだこの世代なら取消として終える(古い世代なら取消の finish が済んでいる)
            if (g == gen && _phase == VoicePhase.PREPARING) cancelledToIdle()
            return
        }
        val info = r.getOrNull()
        if (g != gen || _phase != VoicePhase.PREPARING) {
            // S7: 離脱したあとに作成が成功して届いた。録音せず、直ちに DELETE(id が正しい形のときだけ)。
            // 201 に新しいハンドルがあれば会話の終わりも送る(リスナーには渡さない。今の会話のハンドルを古い世代のもので上書きしない)
            if (info != null) {
                if (VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)) {
                    val id = info.sessionId
                    runBlocking({ bridge.cancel(id) }) { }
                }
                endConversationQuietly(info)
            }
            return
        }
        cancelTimer(Timer.CREATE)
        if (info == null) {
            val e = r.exceptionOrNull()!!
            fail(VoiceFailures.fromException(e), VoiceFailures.diagnostics("create", e))
            return
        }
        if (!VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)) {
            endConversationQuietly(info) // 誰もこのハンドルを持たない。会話の終わりだけ送る
            fail(VoiceErrorCode.HOST_UNREACHABLE, "create: invalid session id")
            return
        }
        sessionId = info.sessionId
        limits = EffectiveLimits.resolve(info.limits)
        // ハンドルの持ち主は呼び出し側。startSse / openMic より前に渡す(openMic が取消で止まる場合でも、この通知は出ている)
        info.conversation?.let { c -> safe { listener.onConversation(tag, c) } }
        startSse()
        if (cuePlan != null) startCue(g) else openMic(g)
    }

    // =========================================================================================
    // 開始の合図(段 G)

    /** 作成のあと、マイクを開く前に合図を鳴らし始める。取消が先に来ていれば鳴らさずに取消として終える */
    private fun startCue(g: Int) {
        val plan = cuePlan ?: return openMic(g)
        if (micPermit.get() != openPermit) {
            cancelledToIdle()
            return
        }
        val greeting = plan.greeting
        val first = if (greeting != null && cueDurationMs(greeting) != null) greeting else null
        safe { listener.onCueStarted(autoStarted, tag, if (first != null) plan.choice else null) }
        if (first != null) playCue(g, first, isGreeting = true) else playChime(g)
    }

    private fun playChime(g: Int) = playCue(g, cuePlan?.chime, isGreeting = false)

    /** 1 本の合図を鳴らす。WAV として読めない・`play` が投げた・`onDone(false)`・期限切れは [onCueDone] の失敗になる */
    private fun playCue(g: Int, wav: ByteArray?, isGreeting: Boolean) {
        val token = ++cueToken
        cueIsGreeting = isGreeting
        val duration = wav?.let { cueDurationMs(it) }
        if (wav == null || duration == null) {
            onCueDone(g, token, false)
            return
        }
        cuePlaying = true
        emit()
        if (_phase != VoicePhase.PREPARING || g != gen) return
        arm(Timer.CUE, duration + VoiceLimits.CUE_PLAYBACK_MARGIN_MS) { onCueDone(g, token, false) }
        try {
            player.play(wav) { ok -> scheduler.post { onCueDone(g, token, ok) } }
        } catch (_: Exception) {
            onCueDone(g, token, false)
        }
    }

    /** 合図の WAV の長さ(実データ)。検査に落ちる・5 秒を超えるなら null */
    private fun cueDurationMs(wav: ByteArray): Long? = try {
        val info = WavValidator.validate(wav)
        if (info.dataBytes.toLong() * 1000 > VoiceLimits.CUE_MAX_MS * info.sampleRate.toLong() * 2) null else info.durationMs
    } catch (_: WavFormatException) {
        null
    }

    private fun onCueDone(g: Int, token: Int, ok: Boolean) {
        if (g != gen || _phase != VoicePhase.PREPARING || token != cueToken) return
        cueToken++ // この再生の通知は 1 回だけ(期限と onDone の両方が来ても)
        cancelTimer(Timer.CUE)
        if (ok) {
            cueResult = MicCue.HEARD
            guardMs = cueGuardMs
            cuePlaying = false
            emit()
            if (_phase != VoicePhase.PREPARING || g != gen) return
            arm(Timer.CUE_GAP, cueEchoWaitMs) { openMic(g) }
            return
        }
        safe { player.stop() }
        if (cueIsGreeting) { // 挨拶を鳴らせなかった: 同じ順序でチャイム
            playChime(g)
            return
        }
        // チャイムも鳴らせなかった: 最後の手段として、マイクを開いた直後に開始音(ビープ)と 400 ms の破棄
        cueResult = MicCue.FAILED
        guardMs = startToneGuardMs
        cuePlaying = false
        emit()
        if (_phase != VoicePhase.PREPARING || g != gen) return
        openMic(g)
    }

    /** 201 のハンドルで、最良努力の会話の終わり(通信はワーカー。結果は無視)。ハンドルが無ければ何もしない */
    private fun endConversationQuietly(info: SessionInfo) {
        val handle = info.conversation?.handle ?: return
        runBlocking({ bridge.endConversation(handle) }) { }
    }

    private fun openMic(g: Int) {
        val permit = openPermit
        val isCurrent = { micPermit.get() == permit }
        if (!isCurrent()) {
            // 取消(離脱・背面・close・stopMicNow)が先に来ていた。録音せず、取消として終える(ホストのセッションは finish が DELETE する)
            cancelledToIdle()
            return
        }
        var failure: Pair<VoiceErrorCode, String>? = null
        try {
            // ロックの外で開始する(`AudioRecord` の作成が遅くても、メインスレッドの取消はこれを待たない)。
            // 開始の最中の取消は AudioSource が公開せずに止める(isCurrent)。取消を見ない実装でも、戻ったあとの照合で止める
            audio.start(
                onPcm = { bytes ->
                    val copy = bytes.copyOf() // AudioRecord の読み取りバッファは使い回される
                    scheduler.post { onPcm(g, copy) }
                },
                onError = { code -> scheduler.post { onMicError(g, code) } },
                isCurrent = isCurrent,
            )
        } catch (e: SecurityException) {
            failure = VoiceErrorCode.MIC_PERMISSION to "mic: permission"
        } catch (e: Exception) {
            failure = VoiceErrorCode.MIC_UNAVAILABLE to "mic: ${e.javaClass.simpleName}"
        }
        if (!isCurrent()) {
            // 開始の最中に取消された。finish が audio.stop() を呼ぶので、AudioSource が静かに無視していても録音は残らない
            cancelledToIdle()
            return
        }
        failure?.let { (code, diag) ->
            fail(code, diag)
            return
        }
        if (!recordingNow()) {
            // start() が同期で onError を投稿していれば、それが先に処理される(精密なコード)。何も言わなければ使用不可。
            // 録音していないので LISTENING にも開始音にも進まない
            scheduler.post { if (g == gen && _phase == VoicePhase.PREPARING) fail(VoiceErrorCode.MIC_UNAVAILABLE, "mic: not recording") }
            return
        }
        micOpen = true
        micOpenedAtMs = clock.nowMs()
        sending = true
        _phase = VoicePhase.LISTENING
        arm(Timer.MIC_CAP, VoiceLimits.MIC_MAX_MS) { onMicCap() }
        arm(Timer.MIC_SILENT, guardMs + VoiceLimits.MIC_SILENT_WINDOW_MS) { onMicSilentCheck() }
        emit() // マイク使用中のスナップショットの通知が失敗したら、ここでセッションが終わっている
        if (!_phase.isMicPhase) return
        try {
            listener.onMicOpened(autoStarted, follow, tag, cueResult)
        } catch (_: Exception) {
            // 開始音を鳴らせなかった(利用者に録音の合図が届かない)まま録音を続けない。例外のメッセージは診断に載せない
            fail(VoiceErrorCode.MIC_UNAVAILABLE, "mic: start tone notify failed")
        }
    }

    private fun onMicError(g: Int, code: VoiceErrorCode) {
        if (g != gen) return
        // マイクを開いている間と、開く途中だけ。録音を終えたあとの通知は無視
        if (_phase.isMicPhase || _phase == VoicePhase.PREPARING) fail(code, "mic: ${code.code}")
    }

    private fun onMicCap() {
        if (!_phase.isMicPhase) return
        stopMic()
        flushing = true // 残りを送り、最後の 1 回に final を付ける
        enterWaiting()
        trySend()
    }

    private fun onMicSilentCheck() {
        if (_phase.isMicPhase && !heardSound) fail(VoiceErrorCode.MIC_SILENT, "mic: silent")
    }

    private fun stopMic() {
        cancelTimer(Timer.MIC_CAP)
        cancelTimer(Timer.MIC_SILENT)
        cancelTimer(Timer.NO_SPEECH)
        safe { audio.stop() }
        micOpen = false
    }

    private fun recordingNow(): Boolean = try { audio.recording } catch (_: Exception) { false }

    // =========================================================================================
    // PCM とチャンク

    private fun onPcm(g: Int, raw: ByteArray) {
        if (g != gen || !_phase.isMicPhase || !micOpen) return
        if (clock.nowMs() - micOpenedAtMs < guardMs) return // 開始音(合図のあとは残響の尾)の間は捨てる
        var bytes = raw
        if (carry >= 0) { // 2 バイト境界を保つ
            bytes = ByteArray(raw.size + 1).also { it[0] = carry.toByte(); System.arraycopy(raw, 0, it, 1, raw.size) }
            carry = -1
        }
        if (bytes.size % 2 == 1) {
            carry = bytes[bytes.size - 1].toInt() and 0xFF
            bytes = bytes.copyOf(bytes.size - 1)
        }
        if (bytes.isEmpty()) return
        if (!heardSound && rms(bytes) > VoiceLimits.SILENT_RMS_MAX) {
            heardSound = true
            cancelTimer(Timer.MIC_SILENT)
        }
        queue.addLast(bytes)
        queuedBytes += bytes.size
        if (queuedBytes > VoiceLimits.QUEUE_MAX_BYTES) { // 黙って古い音声を捨てて送り続けることはしない
            fail(VoiceErrorCode.HOST_UNREACHABLE, "send queue overflow")
            return
        }
        trySend()
    }

    private fun trySend() {
        if (!sending || inFlight != null) return
        val available = queuedBytes
        val final: Boolean
        val take: Int
        if (flushing) {
            take = minOf(available, VoiceLimits.CHUNK_MAX_BYTES)
            final = available <= VoiceLimits.CHUNK_MAX_BYTES
        } else {
            if (available < VoiceLimits.CHUNK_MIN_BYTES) return
            take = minOf(available, VoiceLimits.CHUNK_MAX_BYTES)
            final = false
        }
        var body = ByteArray(take)
        var off = 0
        while (off < take) {
            val head = queue.first()
            val n = minOf(head.size, take - off)
            System.arraycopy(head, 0, body, off, n)
            off += n
            if (n == head.size) queue.removeFirst() else queue[0] = head.copyOfRange(n, head.size)
        }
        queuedBytes -= take
        if (final && body.size < VoiceLimits.CHUNK_MIN_BYTES) { // 契約の最短(20 ms)を守るため、無音で埋める
            body = body.copyOf(VoiceLimits.CHUNK_MIN_BYTES)
        }
        sendChunk(Chunk(nextSeq++, body, final))
    }

    private fun sendChunk(c: Chunk) {
        val g = gen
        val id = sessionId ?: return
        val token = ++requestCounter
        c.token = token
        inFlight = c
        arm(Timer.CHUNK, VoiceLimits.CHUNK_TIMEOUT_MS) { onChunkTimeout(token) }
        runBlocking({ bridge.sendAudio(id, c.seq, c.pcm, c.final) }) { r -> onChunkResult(g, token, r) }
    }

    private fun onChunkTimeout(token: Int) {
        val c = inFlight ?: return
        if (c.token != token) return
        if (!sending) {
            inFlight = null
            return
        }
        // 通信を切る(SSE も切れる。その再接続は回数に数えない)。遅れて届く結果はトークンで捨てる
        if (sseRunning) sseFreeReconnect = true
        safe { bridge.abortInFlight() }
        c.token = 0
        onChunkFailure(c, java.io.IOException("chunk timeout"))
    }

    private fun onChunkResult(g: Int, token: Int, r: Result<ChunkAck>) {
        if (g != gen) return
        val c = inFlight ?: return
        if (c.token != token) return
        cancelTimer(Timer.CHUNK)
        val ack = r.getOrNull()
        if (ack == null) onChunkFailure(c, r.exceptionOrNull()!!) else onChunkAck(c, ack)
    }

    private fun onChunkFailure(c: Chunk, e: Throwable) {
        cancelTimer(Timer.CHUNK)
        if (!sending) { // 録音を止めたあとの失敗は関係ない
            inFlight = null
            return
        }
        if (e is BridgeException && e.kind == BridgeFailure.ENDED) { // S3: 正常な終了の通知
            inFlight = null
            onHostEnded()
            return
        }
        if (VoiceFailures.isRetryable(e) && c.attempt == 0) { // 同じ seq・同じ本文を 1 回だけ
            c.attempt = 1
            sendChunk(c)
            return
        }
        fail(VoiceFailures.fromException(e), VoiceFailures.diagnostics("send", e))
    }

    private fun onChunkAck(c: Chunk, ack: ChunkAck) {
        inFlight = null
        val stale = ack.acceptedSeq < highestAccepted // 累積として扱う。古い ack の state は使わない
        highestAccepted = maxOf(highestAccepted, ack.acceptedSeq)
        if (!sending) return
        if (!firstAckSeen) {
            firstAckSeen = true
            // SSE の speech が最初の ack より先に届いていれば、すでに HEARING(発話中)で、無発話の期限は要らない
            if (_phase == VoicePhase.LISTENING) {
                arm(Timer.NO_SPEECH, limits.noSpeechS * 1000 + VoiceLimits.NO_SPEECH_MARGIN_MS) {
                    fail(VoiceErrorCode.NO_SPEECH, "no speech")
                }
            }
        }
        if (!stale) {
            when (ack.state) {
                AckState.ENDED -> onHostEnded()
                AckState.SPEECH -> onSpeech()
                AckState.LISTENING -> Unit
            }
        }
        if (sending && !c.final) trySend() else if (c.final) sending = false
    }

    // =========================================================================================
    // 前進する遷移(後退しない)

    private fun onSpeech() {
        if (_phase != VoicePhase.LISTENING) return
        _phase = VoicePhase.HEARING
        cancelTimer(Timer.NO_SPEECH)
        emit()
    }

    /** 録音を止める確定信号(`ended` の ack・`409 ended`・`state=waiting`・`reply`)。マイクを止め、未送信を捨てて応答待ちへ。 */
    private fun onHostEnded() {
        if (micOpen) stopMic()
        sending = false
        // 録音を止めたあとは、送信中のチャンクの結果も期限も要らない(期限が切れて abortInFlight すると、応答待ちの SSE や返答音声の取得まで切る)
        cancelTimer(Timer.CHUNK)
        inFlight = null
        queue.clear()
        queuedBytes = 0
        carry = -1
        if (_phase.isMicPhase) enterWaiting()
    }

    private fun enterWaiting() {
        _phase = VoicePhase.WAITING
        cancelTimer(Timer.NO_SPEECH)
        arm(Timer.REPLY, limits.replyTimeoutS * 1000 + VoiceLimits.REPLY_TIMEOUT_MARGIN_MS) {
            fail(VoiceErrorCode.TIMEOUT, "reply timeout")
        }
        emit()
    }

    // =========================================================================================
    // SSE

    private fun startSse() {
        val id = sessionId ?: return
        val g = gen
        val conn = ++sseConn
        val last = if (lastSeq >= 0) lastSeq else null
        sseRunning = true
        runBlocking({ bridge.events(id, last) { ev -> scheduler.post { onEvent(g, ev) } } }) { r -> onStreamEnd(g, conn, r) }
    }

    private fun onStreamEnd(g: Int, conn: Int, r: Result<Unit>) {
        if (g != gen || conn != sseConn) return
        sseRunning = false
        // 終端を受け取った・返答を受け取った・セッションが終わったあとは、再接続しない
        if (sseDone || replyHandled || !_phase.isActive) return
        val e = r.exceptionOrNull()
        if (e is BridgeException && e.kind in NO_RECONNECT) {
            fail(VoiceFailures.fromException(e), VoiceFailures.diagnostics("events", e))
            return
        }
        if (sseFreeReconnect) { // 自分で切った(チャンクのウォッチドッグ)。回数に数えず、すぐ張り直す
            sseFreeReconnect = false
            startSse()
            return
        }
        if (sseReconnects >= VoiceLimits.SSE_MAX_RECONNECTS) {
            fail(VoiceErrorCode.SESSION_LOST, "events lost")
            return
        }
        sseReconnects++
        arm(Timer.SSE_RETRY, backoff.nextDelayMs()) { startSse() }
    }

    private fun onEvent(g: Int, ev: BridgeEvent) {
        if (g != gen || !_phase.isActive) return
        if (ev.seq !in 0..VoiceLimits.MAX_EVENT_SEQ || ev.seq <= lastSeq) return // 重複・再接続後のリプレイ
        lastSeq = ev.seq
        when (ev) {
            is BridgeEvent.State -> when (ev.state) {
                HostState.SPEECH -> onSpeech()
                HostState.WAITING -> onHostEnded()
                HostState.LISTENING -> Unit
            }
            is BridgeEvent.Transcript -> if (!replyHandled) {
                transcript = DaidaiText.clean(ev.text, DaidaiText.MAX_TRANSCRIPT, keepNewlines = false)
                emit()
            }
            is BridgeEvent.Reply -> onReply(ev)
            is BridgeEvent.Error -> {
                if (replyHandled) return // 返答を受け取ったあとの error では捨てない
                val code = VoiceFailures.fromHostCode(ev.code)
                val diag = VoiceFailures.line("host ${ev.code}" + (ev.message?.let { ": $it" } ?: ""))
                if (code != null) fail(code, diag) else finish(VoicePhase.ERROR, null, diag, "host_" + VoiceFailures.reasonFromRaw(ev.code), TurnOutcome.ERROR)
            }
            is BridgeEvent.Done -> {
                sseDone = true
                if (!replyHandled) fail(VoiceErrorCode.SESSION_LOST, "done before reply")
            }
        }
    }

    // =========================================================================================
    // 返答

    private fun onReply(ev: BridgeEvent.Reply) {
        if (ev.index != 0 || replyHandled) return // 初版は index 0 の 1 件だけ
        replyHandled = true
        onHostEnded() // reply も録音を止める確定信号
        cancelTimer(Timer.REPLY)
        reply = DaidaiText.clean(ev.text, VoiceLimits.REPLY_TEXT_MAX_CHARS, keepNewlines = true)
        noticePose = ev.avatarPose?.takeIf { VoiceLimits.POSE_REGEX.matches(it) }
        val audioInfo = ev.audio
        if (audioInfo == null) { // S11: テキストを表示して DONE。`audio_error` があれば(空・未知の文字列も)再生の失敗として固定文言を併記する
            val raw = ev.audioError
            // `audio_error` の有無にかかわらず、音声のない返答は TEXT_ONLY(続けない)
            if (raw == null) finishDone(null, null, TurnOutcome.TEXT_ONLY)
            else finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, VoiceFailures.line("tts: $raw"), TurnOutcome.TEXT_ONLY)
            return
        }
        if (!audioInfo.format.equals("wav", ignoreCase = true) || audioInfo.bytes !in 1..VoiceLimits.REPLY_AUDIO_MAX_BYTES.toLong() ||
            audioInfo.durationMs !in 1..VoiceLimits.REPLY_AUDIO_MAX_MS
        ) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio rejected", TurnOutcome.PLAYBACK_FAILED)
            return
        }
        replyAudio = audioInfo
        _phase = VoicePhase.REPLYING
        emit()
        fetchAudio()
    }

    private fun fetchAudio() {
        val id = sessionId ?: return
        val g = gen
        val token = ++requestCounter
        fetchToken = token
        arm(Timer.REPLY_AUDIO, VoiceLimits.REPLY_AUDIO_FETCH_TIMEOUT_MS) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio timeout", TurnOutcome.PLAYBACK_FAILED)
        }
        runBlocking({ bridge.fetchReplyAudio(id, 0) }) { r -> onAudioFetched(g, token, r) }
    }

    private fun onAudioFetched(g: Int, token: Int, r: Result<ByteArray>) {
        if (g != gen || _phase != VoicePhase.REPLYING || token != fetchToken) return
        cancelTimer(Timer.REPLY_AUDIO)
        val wav = r.getOrNull()
        if (wav == null) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, VoiceFailures.diagnostics("reply audio", r.exceptionOrNull()), TurnOutcome.PLAYBACK_FAILED)
            return
        }
        if (wav.isEmpty() || wav.size > VoiceLimits.REPLY_AUDIO_MAX_BYTES) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio size", TurnOutcome.PLAYBACK_FAILED)
            return
        }
        val duration = playbackMs(wav)
        arm(Timer.PLAYBACK, minOf(duration + VoiceLimits.PLAYBACK_MARGIN_MS, VoiceLimits.REPLY_AUDIO_MAX_MS)) {
            finishDone(null, "playback timeout", TurnOutcome.PLAYBACK_TIMEOUT) // 停止して DONE
        }
        try {
            player.play(wav) { ok -> scheduler.post { onPlaybackDone(g, ok) } }
        } catch (e: Exception) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, VoiceFailures.diagnostics("play", e), TurnOutcome.PLAYBACK_FAILED)
        }
    }

    /**
     * 再生の期限のもとにする長さ。**実データから求める**(宣言の `duration_ms` は参考: 「宣言 1 秒・実際 20 秒」の正常な WAV を途中で止めない)。
     * 宣言と食い違っていてもエラーにせず実データを採用する。上限(120 秒)は検証済みの WAV なら [WavValidator] が保証している。
     * 検証済みでない(WAV として読めない)バイト列のときだけ、宣言を使う(実クライアントは常に検証済みを返す)。
     */
    private fun playbackMs(wav: ByteArray): Long =
        try {
            WavValidator.validate(wav).durationMs
        } catch (_: WavFormatException) {
            replyAudio?.durationMs ?: VoiceLimits.REPLY_AUDIO_MAX_MS
        }

    private fun onPlaybackDone(g: Int, ok: Boolean) {
        if (g != gen || _phase != VoicePhase.REPLYING) return
        if (ok) finishDone(null, null, TurnOutcome.PLAYED) else finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "playback failed", TurnOutcome.PLAYBACK_FAILED)
    }

    // =========================================================================================
    // 画面への通知

    private fun displayState(): VoiceState = when (_phase) {
        VoicePhase.IDLE, VoicePhase.PREPARING, VoicePhase.DONE -> VoiceState.IDLE
        VoicePhase.LISTENING -> VoiceState.LISTENING
        VoicePhase.HEARING -> VoiceState.HEARING
        VoicePhase.WAITING -> VoiceState.WAITING
        VoicePhase.REPLYING -> VoiceState.REPLYING
        VoicePhase.ERROR -> VoiceState.ERROR
    }

    private fun emit() {
        val snap = VoiceSnapshot(
            state = displayState(), transcript = transcript, reply = reply, error = error,
            diagnostics = diagnostics, micActive = recordingNow(), noticePose = noticePose, active = _phase.isActive,
            cuePlaying = cuePlaying && _phase == VoicePhase.PREPARING,
        )
        if (snap == lastSnapshot) return
        lastSnapshot = snap
        if (snap.micActive && _phase.isActive) {
            // 録音中の必須の通知: 表示(マイク使用中)が更新できないまま録音を続けない。例外のメッセージは診断に載せない
            try {
                listener.onSnapshot(snap)
            } catch (_: Exception) {
                fail(VoiceErrorCode.MIC_UNAVAILABLE, "mic: display notify failed")
            }
        } else {
            // 終了処理中(ERROR・DONE・取消のあと)の通知は、失敗しても後ろの停止を進める
            safe { listener.onSnapshot(snap) }
        }
    }

    // =========================================================================================
    // 部品

    /** ブロッキングの呼び出しをワーカーで走らせ、結果を現在の世代つきでセッションのスレッドに戻す。ワーカー自体の失敗も結果として戻す */
    private fun <T> runBlocking(call: () -> T, deliver: (Result<T>) -> Unit) {
        try {
            worker.run(call, deliver)
        } catch (e: Exception) {
            scheduler.post { deliver(Result.failure(e)) }
        }
    }

    /** 世代つきのタイマー。同じ種類を張り直すと前のものは捨てる。現在の世代・最新のトークンでなければ何もしない */
    private fun arm(timer: Timer, delayMs: Long, action: () -> Unit) {
        cancelTimer(timer)
        val g = gen
        val token = ++timerCounter
        timerTokens[timer] = token
        timers[timer] = scheduler.postDelayed({
            if (g == gen && timerTokens[timer] == token) {
                timers.remove(timer)
                timerTokens.remove(timer)
                action()
            }
        }, delayMs)
    }

    private fun cancelTimer(timer: Timer) {
        timerTokens.remove(timer)
        timers.remove(timer)?.let { safe { it.cancel() } }
    }

    private fun cancelAllTimers() {
        for (t in Timer.values()) cancelTimer(t)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // 停止の途中の例外で、後ろの手順を飛ばさない
        }
    }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            n++
            i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    companion object {
        const val DEFAULT_CLIENT_ID = "echo-show-5"

        /** 再接続しても無駄な失敗(`404`・`410`・`4xx`) */
        private val NO_RECONNECT = setOf(BridgeFailure.NOT_FOUND, BridgeFailure.EXPIRED, BridgeFailure.CLIENT_ERROR, BridgeFailure.ASR_UNAVAILABLE)
    }
}
