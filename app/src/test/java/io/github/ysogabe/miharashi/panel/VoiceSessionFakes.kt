package io.github.ysogabe.miharashi.panel

import java.io.IOException
import java.util.PriorityQueue

/**
 * VoiceSession のテスト用の決定的なフェイク一式。時刻は仮想時間(ミリ秒)で、[Sim.advance] で進める。
 * スレッドは使わない: セッションのスレッド = テストのスレッド。ワーカーの `call` は投稿の時点で同期に実行し、
 * 結果の配達だけを「通信の遅さ」の分だけ仮想時間で遅らせる(遅延 null = 応答が来ない。`abortInFlight` か明示の完了で戻る)。
 */
class Sim : SessionScheduler, Clock {
    private class Task(val at: Long, val order: Long, val run: () -> Unit) {
        var cancelled = false
    }

    var now = 0L
        private set
    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.order }))
    private var counter = 0L

    override fun nowMs() = now

    override fun post(task: () -> Unit) {
        tasks += Task(now, counter++, task)
    }

    override fun postDelayed(task: () -> Unit, delayMs: Long): Cancelable {
        val t = Task(now + maxOf(0L, delayMs), counter++, task)
        tasks += t
        return Cancelable { t.cancelled = true }
    }

    /** 今この時刻に実行できるものをすべて実行する */
    fun settle() = advance(0)

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val t = tasks.peek() ?: break
            if (t.at > target) break
            tasks.poll()
            if (t.cancelled) continue
            now = maxOf(now, t.at)
            t.run()
        }
        now = target
    }
}

/** フェイクのブリッジの 1 回の応答: `latencyMs` 後に `result` を返す(投げる)。null は応答なし(中断されるまで戻らない) */
class Resp<T>(val latencyMs: Long?, val abortable: Boolean = true, val result: () -> T)

fun <T> ok(value: T, latencyMs: Long = 0, abortable: Boolean = true) = Resp(latencyMs, abortable) { value }
fun <T> failWith(e: Throwable, latencyMs: Long = 0) = Resp<T>(latencyMs) { throw e }
fun <T> hang() = Resp<T>(null) { error("unreachable") }

fun bridgeError(kind: BridgeFailure, status: Int = 0, code: String? = null, message: String? = null) =
    BridgeException(kind, status, code, message)

class SentChunk(val seq: Int, val pcm: ByteArray, val final: Boolean, val atMs: Long, val attempt: Int)

class FakeBridge(private val sim: Sim, private val log: MutableList<String>) : BridgeApi {
    lateinit var worker: FakeWorker
    val hostLimits = HostLimits(noSpeechS = 10, maxUtteranceS = 30, replyTimeoutS = 45, idleExpireS = 15, retainS = 60)

    var createReply: (Int) -> Resp<SessionInfo> = { n -> ok(SessionInfo("s-$n", hostLimits)) }
    var chunkReply: (SentChunk) -> Resp<ChunkAck> = { ok(ChunkAck(AckState.LISTENING, it.seq)) }
    var fetchReply: (Int) -> Resp<ByteArray> = { ok(ByteArray(2000)) }
    var cancelReply: Resp<Unit> = ok(Unit)

    val created = mutableListOf<SessionRequest>()
    val chunks = mutableListOf<SentChunk>()
    val cancels = mutableListOf<String>()
    val eventOpens = mutableListOf<Int?>()
    val fetches = mutableListOf<Int>()
    var aborts = 0
    var sendsInFlight = 0
    var maxConcurrentSends = 0
    private val attempts = HashMap<Int, Int>()

    /** ホスト側のイベントの記録(再接続のときに `Last-Event-ID` の次から再生する) */
    val hostLog = mutableListOf<BridgeEvent>()
    private var seqCounter = 0

    class Stream(val onEvent: (BridgeEvent) -> Unit, val pending: FakeWorker.Pending)

    val streams = mutableListOf<Stream>()

    // ワーカーが読む直前の呼び出しの情報
    var info = FakeWorker.CallInfo(0L, true)
    var currentPending: FakeWorker.Pending? = null

    fun nextSeq() = seqCounter++

    /** ホストがイベントを出す: 記録して、つながっている購読へ流す */
    fun emit(ev: BridgeEvent) {
        hostLog += ev
        streams.toList().forEach { it.onEvent(ev) }
    }

    /** 記録せずに購読へ流す(重複・リプレイの再現) */
    fun emitRaw(ev: BridgeEvent) = streams.toList().forEach { it.onEvent(ev) }

    /** 購読を閉じる(EOF なら成功、例外ならその例外) */
    fun endStreams(result: Result<Unit> = Result.success(Unit)) {
        val s = streams.toList()
        streams.clear()
        s.forEach { worker.complete(it.pending, result) }
    }

    override fun createSession(req: SessionRequest): SessionInfo {
        log += "create"
        created += req
        hostLog.clear() // ホストのイベントの記録はセッションごと
        seqCounter = 0
        val r = createReply(created.size)
        info = FakeWorker.CallInfo(r.latencyMs, r.abortable)
        return r.result()
    }

    override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean): ChunkAck {
        val attempt = attempts.merge(seq, 1, Int::plus)!! - 1
        val c = SentChunk(seq, pcm.copyOf(), final, sim.now, attempt)
        chunks += c
        log += "send:$seq"
        sendsInFlight++
        maxConcurrentSends = maxOf(maxConcurrentSends, sendsInFlight)
        val r = chunkReply(c)
        info = FakeWorker.CallInfo(r.latencyMs, r.abortable) { sendsInFlight-- }
        return r.result()
    }

    override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {
        log += "events"
        eventOpens += lastEventId
        val replay = hostLog.filter { lastEventId == null || it.seq > lastEventId }
        replay.forEach(onEvent)
        streams += Stream(onEvent, currentPending!!)
        info = FakeWorker.CallInfo(null, true) // 購読は閉じられるまで戻らない
    }

    override fun fetchReplyAudio(id: String, index: Int): ByteArray {
        log += "fetch"
        fetches += index
        val r = fetchReply(fetches.size)
        info = FakeWorker.CallInfo(r.latencyMs, r.abortable)
        return r.result()
    }

    override fun cancel(id: String) {
        log += "cancel:$id"
        cancels += id
        info = FakeWorker.CallInfo(cancelReply.latencyMs, abortable = false)
        cancelReply.result()
    }

    override fun abortInFlight() {
        log += "abort"
        aborts++
        worker.abortAll()
    }
}

class FakeWorker(private val sim: Sim, private val bridge: FakeBridge) : SessionWorker {
    class CallInfo(val latencyMs: Long?, val abortable: Boolean, val onComplete: (() -> Unit)? = null)

    class Pending(val deliver: (Result<Any?>) -> Unit) {
        var abortable = true
        var done = false
        var timer: Cancelable? = null
        var onComplete: (() -> Unit)? = null
    }

    private val inflight = mutableListOf<Pending>()

    /** 応答なし(遅延 null)で止まっている呼び出しの数 */
    val heldCount: Int get() = inflight.count { it.timer == null }

    @Suppress("UNCHECKED_CAST")
    override fun <T> run(call: () -> T, deliver: (Result<T>) -> Unit) {
        val p = Pending(deliver as (Result<Any?>) -> Unit)
        bridge.currentPending = p
        bridge.info = CallInfo(0L, true)
        val result = runCatching(call)
        bridge.currentPending = null
        val info = bridge.info
        p.abortable = info.abortable
        p.onComplete = info.onComplete
        inflight += p
        if (info.latencyMs != null) {
            p.timer = sim.postDelayed({ complete(p, result) }, info.latencyMs)
        }
    }

    fun complete(p: Pending, result: Result<Any?>) {
        if (p.done) return
        p.done = true
        p.timer?.cancel()
        inflight -= p
        p.onComplete?.invoke()
        sim.post { p.deliver(result) }
    }

    /** `abortInFlight` の再現: 中断できる呼び出しをすべて通信失敗で戻らせる(取消の DELETE は対象外) */
    fun abortAll() {
        inflight.filter { it.abortable }.forEach { complete(it, Result.failure(IOException("aborted"))) }
    }
}

class FakeAudio(private val sim: Sim, private val log: MutableList<String>) : AudioSource {
    private var isRecording = false
    override val recording: Boolean get() = isRecording
    var startCount = 0
    var stopCount = 0
    val stopTimes = mutableListOf<Long>()
    var throwOnStop = false
    var startError: VoiceErrorCode? = null
    var startThrows: Exception? = null

    /** `start` が何も言わずに、録音も始めない */
    var silentFailure = false
    private var onPcm: ((ByteArray) -> Unit)? = null
    var onError: ((VoiceErrorCode) -> Unit)? = null

    override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) {
        log += "audio.start"
        startCount++
        startThrows?.let { throw it }
        this.onPcm = onPcm
        this.onError = onError
        startError?.let { onError(it); return }
        if (silentFailure) return
        isRecording = true
    }

    override fun stop() {
        log += "audio.stop"
        stopCount++
        stopTimes += sim.now
        isRecording = false
        if (throwOnStop) throw IllegalStateException("stop failed")
    }

    /** 録音中なら、`ms` ミリ秒ぶん(波形は ±amp の矩形)の PCM を渡す */
    fun feed(ms: Int, amp: Int = 1000) {
        if (isRecording) onPcm?.invoke(pcm(ms, amp))
    }

    fun feedBytes(n: Int, amp: Int = 1000) {
        if (isRecording) onPcm?.invoke(pcmBytes(n, amp))
    }

    companion object {
        fun pcm(ms: Int, amp: Int) = pcmBytes(ms * 32, amp)

        fun pcmBytes(n: Int, amp: Int): ByteArray {
            val b = ByteArray(n)
            var i = 0
            var sign = 1
            while (i + 1 < n) {
                val s = amp * sign
                b[i] = (s and 0xFF).toByte()
                b[i + 1] = ((s shr 8) and 0xFF).toByte()
                sign = -sign
                i += 2
            }
            return b
        }
    }
}

class FakePlayer(private val log: MutableList<String>) : ReplyPlayer {
    var plays = 0
    var stopCount = 0
    var lastWav: ByteArray? = null
    var throwOnPlay: Exception? = null
    private var onDone: ((Boolean) -> Unit)? = null
    val playing: Boolean get() = onDone != null

    override fun play(wav: ByteArray, onDone: (Boolean) -> Unit) {
        log += "player.play"
        throwOnPlay?.let { throw it }
        plays++
        lastWav = wav
        this.onDone = onDone
    }

    override fun stop() {
        log += "player.stop"
        stopCount++
        onDone = null
    }

    /** 再生が終わった(別スレッドからの呼び出しの再現のため、ここでは単に呼ぶ) */
    fun finish(ok: Boolean) {
        val cb = onDone ?: return
        onDone = null
        cb(ok)
    }
}

class RecordingListener(private val sim: Sim, private val log: MutableList<String>) : VoiceSessionListener {
    val snaps = mutableListOf<VoiceSnapshot>()
    val micOpenedAt = mutableListOf<Long>()
    val ended = mutableListOf<Pair<Boolean, String>>()

    override fun onSnapshot(snapshot: VoiceSnapshot) {
        log += "snapshot:${snapshot.state}"
        snaps += snapshot
    }

    override fun onMicOpened() {
        log += "micOpened"
        micOpenedAt += sim.now
    }

    override fun onEnded(autoStarted: Boolean, reason: String) {
        log += "ended:$reason"
        ended += autoStarted to reason
    }
}

/** セッション・フェイク一式と、テストを読みやすくする操作 */
class Rig(guardMs: Long = VoiceLimits.START_TONE_GUARD_MS, sessionMaxMs: Long = VoiceLimits.SESSION_MAX_MS) {
    val log = mutableListOf<String>()
    val sim = Sim()
    val bridge = FakeBridge(sim, log)
    val worker = FakeWorker(sim, bridge).also { bridge.worker = it }
    val audio = FakeAudio(sim, log)
    val player = FakePlayer(log)
    val listener = RecordingListener(sim, log)
    val session = VoiceSession(
        audio, bridge, player, sim, sim, worker, listener, clientId = "echo-show-5", appVersion = "0.3.0",
        startToneGuardMs = guardMs, sessionMaxMs = sessionMaxMs,
    )

    /** チャンクの応答の `state`(既定の [FakeBridge.chunkReply] が使う) */
    var ackState = AckState.LISTENING

    init {
        bridge.chunkReply = { ok(ChunkAck(ackState, it.seq)) }
    }

    val phase: VoicePhase get() = session.phase
    val last: VoiceSnapshot get() = listener.snaps.last()

    fun start(reason: StartReason = StartReason.SWIPE, notice: String? = null, auto: Boolean = false) {
        session.start(reason, notice, auto)
        sim.settle()
    }

    fun cancel() {
        session.cancel()
        sim.settle()
    }

    fun retry() {
        session.retry()
        sim.settle()
    }

    /** 20 ms(既定)ごとに PCM を渡しながら `ms` ミリ秒進める */
    fun talk(ms: Int, amp: Int = 1000, block: Int = 20) {
        var left = ms
        while (left > 0) {
            val b = minOf(block, left)
            audio.feed(b, amp)
            sim.advance(b.toLong())
            left -= b
        }
    }

    fun advance(ms: Long) = sim.advance(ms)

    fun hostEmit(make: (Int) -> BridgeEvent) {
        bridge.emit(make(bridge.nextSeq()))
        sim.settle()
    }

    fun speech() = hostEmit { BridgeEvent.State(it, HostState.SPEECH) }
    fun waiting() = hostEmit { BridgeEvent.State(it, HostState.WAITING) }
    fun transcript(text: String, final: Boolean = true) = hostEmit { BridgeEvent.Transcript(it, text, final) }
    fun done() = hostEmit { BridgeEvent.Done(it) }
    fun hostError(code: String, message: String? = null) = hostEmit { BridgeEvent.Error(it, code, message) }

    fun reply(
        text: String = "こんにちは",
        audio: ReplyAudioInfo? = ReplyAudioInfo("wav", 24_000, 2000, 4200),
        audioError: String? = null,
        index: Int = 0,
        pose: String? = "talking",
    ) = hostEmit { BridgeEvent.Reply(it, index, text, audio, audioError, true, pose) }

    /** 状態の遷移の並び(連続した同じ値は 1 つに) */
    fun states(): List<VoiceState> = listener.snaps.map { it.state }.fold(mutableListOf()) { acc, s ->
        if (acc.lastOrNull() != s) acc += s
        acc
    }

    /** 開始から LISTENING になるまで進める(作成の遅延 0) */
    fun startAndListen(reason: StartReason = StartReason.SWIPE, notice: String? = null, auto: Boolean = false) {
        start(reason, notice, auto)
        check(phase == VoicePhase.LISTENING) { "phase=$phase" }
    }
}
