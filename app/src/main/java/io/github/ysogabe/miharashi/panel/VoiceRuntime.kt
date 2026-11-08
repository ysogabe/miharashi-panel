package io.github.ysogabe.miharashi.panel

import java.util.concurrent.Executor

/**
 * マイク(設計書 §5.4)。実体は T8 の `MicAudioSource`(`AudioRecord`、16 kHz / mono / PCM16)。
 *
 * - [start] は**同期**: 戻った時点で録音を始めている([recording] が true)か、失敗を `onError` で知らせている。
 *   `onError` は同期でも別スレッドでも呼んでよい。コードは `mic_permission`(権限なし)/ `mic_unavailable`(使用不可)。
 *   録音中に読み取りが失敗したときも `onError` を呼んで止める。
 * - `onPcm` はオーディオスレッドから呼ばれる。渡す配列は VoiceSession が**すぐコピーする**ので使い回してよい。
 *   1 回の大きさは 100 ms(3,200 バイト)前後を勧める(20 ms 刻みだとチャンクが細かくなりすぎる)。
 * - [stop] は**停止と release**を兼ねる。冪等(録音していなくても呼べる)・ノンブロッキング・通信に依存しない。
 *   停止が内部で失敗して例外を投げても、呼び出し側(VoiceSession)は続行する(通信の中断・`DELETE`・再生停止を飛ばさない)。
 *   そのため実装は、失敗しても `finally` で**必ず release する**(録音・リソースを残さない)。
 *   **T8 の `MicAudioSource` の注意**: `AudioRecord.stop()` が例外を投げる状況で `release()` が呼ばれ、
 *   [recording] が false に戻ることを、`MicAudioSource` 自身のテストで確認すること(VoiceSession のテストは
 *   録音状態を false にしてから投げるフェイクを使うので、release の保証までは確かめていない)。
 * - [recording] は実際の録音状態(`recordingState == RECORDSTATE_RECORDING`)。画面の「● マイク使用中」はこれに連動する。
 */
interface AudioSource {
    fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit)
    fun stop()
    val recording: Boolean
}

/**
 * 返答の WAV の再生(T8 の `WavPlayer`)。[play] はノンブロッキングで、再生を始めたら戻る。
 * `onDone(true)` は最後まで再生、`onDone(false)` は検証・再生の失敗。**どのスレッドから呼んでもよい**(VoiceSession がセッションのスレッドへ戻す)。
 * [stop] は冪等で、止めたあとの `onDone` は呼ばなくてよい(呼んでも無視される)。ファイルに書かない。
 */
interface ReplyPlayer {
    fun play(wav: ByteArray, onDone: (Boolean) -> Unit)
    fun stop()
}

/** 単調増加のミリ秒(`SystemClock.elapsedRealtime()`)。 */
fun interface Clock {
    fun nowMs(): Long
}

/** [SessionScheduler.postDelayed] の取り消し。 */
fun interface Cancelable {
    fun cancel()
}

/**
 * セッション専用のスレッド(本番は `HandlerThread("daidai-session")` の `Handler`)。[VoiceSession] の全入力はここで**直列に**処理される。
 * `post` / `postDelayed` はどのスレッドから呼んでもよく、投稿順に実行する(`postDelayed(r, 0)` と `post` の順序は問わない)。
 * [Cancelable.cancel] は実行前なら取り消し、実行後なら何もしない。
 */
interface SessionScheduler {
    fun post(task: () -> Unit)
    fun postDelayed(task: () -> Unit, delayMs: Long): Cancelable
}

/**
 * ブロッキング通信を別スレッドで走らせる。[run] は `call` をワーカースレッドで実行し、結果(または投げた例外)を
 * **セッションのスレッドで** `deliver` に渡す。本番は [ExecutorSessionWorker]。
 * 同時に 3 本以上走れること(SSE が 1 本を占有し、その間にチャンクの送信と、再送・取消が走る)。
 */
interface SessionWorker {
    fun <T> run(call: () -> T, deliver: (Result<T>) -> Unit)
}

/** [Executor](本番はキャッシュ型のスレッドプール)で動かす [SessionWorker]。結果は [scheduler] に post して戻す。 */
class ExecutorSessionWorker(private val executor: Executor, private val scheduler: SessionScheduler) : SessionWorker {
    override fun <T> run(call: () -> T, deliver: (Result<T>) -> Unit) {
        try {
            executor.execute {
                val result = runCatching(call)
                scheduler.post { deliver(result) }
            }
        } catch (e: Exception) { // RejectedExecutionException など
            scheduler.post { deliver(Result.failure(e)) }
        }
    }
}

/**
 * 画面へ渡すスナップショット(不変)。[VoiceState] は表示用(PREPARING / DONE は IDLE に畳む)。
 * `error` は `state == ERROR` のとき、ホストの未知のコードなら null(画面は汎用の文言にする)。`DONE`(表示は `IDLE`)でも、
 * 返答音声の失敗(取得・検証・再生の失敗、`audio: null` に `audio_error` が付いた場合を含む)は `error = REPLY_AUDIO_FAILED` を載せ、
 * `reply` のテキストも残す。画面は返答の下に固定文言を併記する。`toString` に本文を含めない。
 */
data class VoiceSnapshot(
    val state: VoiceState = VoiceState.IDLE,
    val transcript: String = "",
    val reply: String = "",
    val error: VoiceErrorCode? = null,
    /** ホストの `message` などの診断の 1 行(画面の本文には出さない) */
    val diagnostics: String? = null,
    /** `AudioSource.recording` の実際の値 */
    val micActive: Boolean = false,
    /** 返答の `avatar_pose`(検証済み。未知の名前は画面側が `idle` にする) */
    val noticePose: String? = null,
    /**
     * セッションが進行中か(PREPARING〜REPLYING)。表示用の [state] は PREPARING を IDLE に畳むので、
     * B の無操作タイマーを止める判定([PageController.sessionActive])はこの値を使う。
     */
    val active: Boolean = false,
) {
    override fun toString() = "VoiceSnapshot(state=$state, transcript=${transcript.length}chars, reply=${reply.length}chars, " +
        "error=$error, mic=$micActive, active=$active)"
}

/** [VoiceSession] の通知。**セッションのスレッドで呼ばれる**ので、UI への反映は呼び出し側が main へ post する。 */
interface VoiceSessionListener {
    /** 表示が変わるたびに。同じ内容は続けて呼ばない */
    fun onSnapshot(snapshot: VoiceSnapshot)

    /**
     * マイクを開いた(録音を始めた)直後。T8 はここで開始音(`ToneGenerator`)を鳴らす。
     * 開始音と録音の順序の方針: マイクを先に開き、鳴らす間の [VoiceLimits.START_TONE_GUARD_MS] は録音を捨てる(送らない)。
     */
    fun onMicOpened()

    /**
     * セッションが終わった(DONE・ERROR・取消のどれか)ときに**セッションごとに 1 回だけ**。ERROR から IDLE へ戻るときは呼ばない。
     * `reason`: DONE は "done"、取消は "cancelled"、ERROR はエラーコードの文字列(例 "no_speech"。`AutoSwitchPolicy.REASON_NO_SPEECH` と一致)。
     * ホストの未知のコードは `host_` + `[a-z0-9_]` だけに整えた文字列(本物のコードと衝突しない。例 "host_weird_code"、整えると空なら "host_unknown")。
     * 返答音声の失敗(`audio: null` に `audio_error` が付いた場合を含む)で DONE になるときは "reply_audio_failed"。
     *
     * 録音中(マイクを開いている間)にこのリスナーの [onMicOpened]・[onSnapshot] が例外を投げると、セッションは録音を続けず
     * `mic_unavailable` で終わる。終了処理中の通知の例外は無視される。
     */
    fun onEnded(autoStarted: Boolean, reason: String)
}
