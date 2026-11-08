package io.github.ysogabe.miharashi.panel

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
 * ## 停止の順序(どの状態からでも同じ。[finish])
 * 世代を進める → マイクを止めて release → `abortInFlight()` → 最良努力で `cancel(id)` → 再生停止 → 画面に反映 → `onEnded`。
 * 各段は例外を握りつぶして次へ進む。**マイクの停止は通信の完了に依存しない。**
 * DONE・ERROR・取消のどれで終わるときも同じ手順で、ホストにはセッションの削除(`DELETE`)を送る(認識テキストを早く手放させる)。
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
) {
    private enum class Timer { TOTAL, CREATE, CHUNK, MIC_CAP, MIC_SILENT, NO_SPEECH, REPLY, REPLY_AUDIO, PLAYBACK, SSE_RETRY, ERROR_DISPLAY }

    private class Chunk(val seq: Int, val pcm: ByteArray, val final: Boolean) {
        var attempt = 0
        var token = 0
    }

    // ---- セッション共通(セッションのスレッドでだけ触る)
    private var gen = 0
    private var _phase = VoicePhase.IDLE
    private var sessionId: String? = null
    private var autoStarted = false
    private var startReason = StartReason.SWIPE
    private var noticeId: String? = null
    private var limits = EffectiveLimits.DEFAULTS
    private var transcript = ""
    private var reply = ""
    private var error: VoiceErrorCode? = null
    private var diagnostics: String? = null
    private var noticePose: String? = null
    private var lastSnapshot: VoiceSnapshot? = null
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

    /** 現在の内部状態。**セッションのスレッドで読む**(テスト用の公開) */
    val phase: VoicePhase get() = _phase

    // =========================================================================================
    // 公開 API(どのスレッドからでも)

    /**
     * B の確定で呼ぶ。進行中(PREPARING〜REPLYING)のときは**無視**する(同時に 1 セッション。呼び側は先に [cancel] する)。
     * IDLE・DONE・ERROR からは新しいセッションを始める。`noticeId` が `^[A-Za-z0-9_.:-]{1,64}$` に合わなければ送らない。
     */
    fun start(reason: StartReason, noticeId: String?, autoStarted: Boolean) {
        scheduler.post { doStart(reason, noticeId, autoStarted) }
    }

    /** B を離れる・背面・待機・設定画面。どの状態からでもマイクを止めて release し、IDLE に戻す。冪等。 */
    fun cancel() {
        scheduler.post { doCancel() }
    }

    /** ERROR 表示のタップ。直前と同じ起点で新しいセッションを始める(利用者の操作なので `autoStarted = false`)。ERROR 以外では何もしない。 */
    fun retry() {
        scheduler.post {
            if (_phase == VoicePhase.ERROR) doStart(startReason, noticeId, auto = false)
        }
    }

    // =========================================================================================
    // 開始と終了

    private fun doStart(reason: StartReason, notice: String?, auto: Boolean) {
        if (_phase.isActive) return
        resetForNewSession()
        startReason = reason
        noticeId = notice?.takeIf { VoiceLimits.NOTICE_ID_REGEX.matches(it) }
        autoStarted = auto
        _phase = VoicePhase.PREPARING
        val g = gen
        arm(Timer.TOTAL, sessionMaxMs) { onTotalTimeout() }
        arm(Timer.CREATE, VoiceLimits.CREATE_TIMEOUT_MS) { fail(VoiceErrorCode.HOST_UNREACHABLE, "create timeout") }
        emit()
        val req = SessionRequest(clientId, appVersion, reason, noticeId)
        runBlocking({ bridge.createSession(req) }) { r -> onCreated(g, r) }
    }

    private fun doCancel() {
        if (_phase.isActive) {
            finish(VoicePhase.IDLE, null, null, "cancelled")
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
    private fun finish(next: VoicePhase, err: VoiceErrorCode?, diag: String?, reason: String) {
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
        safe { player.stop() } // 5. 再生を止める
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
        if (wasActive) safe { listener.onEnded(autoStarted, reason) }
    }

    private fun fail(code: VoiceErrorCode, diag: String? = null) = finish(VoicePhase.ERROR, code, diag, code.code)

    private fun finishDone(err: VoiceErrorCode?, diag: String?) = finish(VoicePhase.DONE, err, diag, err?.code ?: "done")

    private fun errorToIdle() {
        if (_phase != VoicePhase.ERROR) return
        clearOutputs()
        _phase = VoicePhase.IDLE
        emit()
    }

    private fun onTotalTimeout() {
        // 返答が手元にあるなら成功として閉じる。それ以外は取消して timeout
        if (_phase == VoicePhase.REPLYING) finishDone(null, "session time limit") else fail(VoiceErrorCode.TIMEOUT, "session time limit")
    }

    // =========================================================================================
    // 作成とマイク

    private fun onCreated(g: Int, r: Result<SessionInfo>) {
        val info = r.getOrNull()
        if (g != gen || _phase != VoicePhase.PREPARING) {
            // S7: 離脱したあとに作成が成功して届いた。録音せず、直ちに DELETE(id が正しい形のときだけ)
            if (info != null && VoiceLimits.SESSION_ID_REGEX.matches(info.sessionId)) {
                val id = info.sessionId
                runBlocking({ bridge.cancel(id) }) { }
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
            fail(VoiceErrorCode.HOST_UNREACHABLE, "create: invalid session id")
            return
        }
        sessionId = info.sessionId
        limits = EffectiveLimits.resolve(info.limits)
        startSse()
        openMic(g)
    }

    private fun openMic(g: Int) {
        try {
            audio.start(
                onPcm = { bytes ->
                    val copy = bytes.copyOf() // AudioRecord の読み取りバッファは使い回される
                    scheduler.post { onPcm(g, copy) }
                },
                onError = { code -> scheduler.post { onMicError(g, code) } },
            )
        } catch (e: SecurityException) {
            fail(VoiceErrorCode.MIC_PERMISSION, "mic: permission")
            return
        } catch (e: Exception) {
            fail(VoiceErrorCode.MIC_UNAVAILABLE, "mic: ${e.javaClass.simpleName}")
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
        arm(Timer.MIC_SILENT, startToneGuardMs + VoiceLimits.MIC_SILENT_WINDOW_MS) { onMicSilentCheck() }
        emit()
        safe { listener.onMicOpened() }
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
        if (clock.nowMs() - micOpenedAtMs < startToneGuardMs) return // 開始音の間は捨てる
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
            if (_phase.isMicPhase) {
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
                if (code != null) fail(code, diag) else finish(VoicePhase.ERROR, null, diag, VoiceFailures.reasonFromRaw(ev.code))
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
        if (audioInfo == null) { // S11: テキストだけ表示して DONE
            finishDone(null, ev.audioError?.let { VoiceFailures.line("tts: $it") })
            return
        }
        if (!audioInfo.format.equals("wav", ignoreCase = true) || audioInfo.bytes !in 1..VoiceLimits.REPLY_AUDIO_MAX_BYTES.toLong() ||
            audioInfo.durationMs !in 1..VoiceLimits.REPLY_AUDIO_MAX_MS
        ) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio rejected")
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
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio timeout")
        }
        runBlocking({ bridge.fetchReplyAudio(id, 0) }) { r -> onAudioFetched(g, token, r) }
    }

    private fun onAudioFetched(g: Int, token: Int, r: Result<ByteArray>) {
        if (g != gen || _phase != VoicePhase.REPLYING || token != fetchToken) return
        cancelTimer(Timer.REPLY_AUDIO)
        val wav = r.getOrNull()
        if (wav == null) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, VoiceFailures.diagnostics("reply audio", r.exceptionOrNull()))
            return
        }
        if (wav.isEmpty() || wav.size > VoiceLimits.REPLY_AUDIO_MAX_BYTES) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "reply audio size")
            return
        }
        val duration = replyAudio?.durationMs ?: VoiceLimits.REPLY_AUDIO_MAX_MS
        arm(Timer.PLAYBACK, minOf(duration + VoiceLimits.PLAYBACK_MARGIN_MS, VoiceLimits.REPLY_AUDIO_MAX_MS)) {
            finishDone(null, "playback timeout") // 停止して DONE
        }
        try {
            player.play(wav) { ok -> scheduler.post { onPlaybackDone(g, ok) } }
        } catch (e: Exception) {
            finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, VoiceFailures.diagnostics("play", e))
        }
    }

    private fun onPlaybackDone(g: Int, ok: Boolean) {
        if (g != gen || _phase != VoicePhase.REPLYING) return
        if (ok) finishDone(null, null) else finishDone(VoiceErrorCode.REPLY_AUDIO_FAILED, "playback failed")
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
            diagnostics = diagnostics, micActive = recordingNow(), noticePose = noticePose,
        )
        if (snap == lastSnapshot) return
        lastSnapshot = snap
        safe { listener.onSnapshot(snap) }
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
