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
 * - **[stop] は [start] の完了を待たない**(別スレッドで [start] が実行中でも、ロック待ちなどで呼び出し側を止めない。メインスレッドから呼ばれる)。
 *   [start] の実行中に [stop] が呼ばれたら、実装は取消を覚えて即座に戻り、[start] の側が、作成・開始が済んだ時点で録音を止めて release し、
 *   `onError` も `onPcm` も呼ばずに静かに戻る(そのとき [recording] は false)。
 */
interface AudioSource {
    fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit)

    /**
     * [start] の取消つき版。[isCurrent] が false を返したら(呼び出し側が取り消した)、実装は**録音を始めず**(始めていたら直ちに止めて release し)、
     * `onError` も `onPcm` も呼ばずに戻る。確認は録音を始める直前と公開の直前に行う。既定は取消を見ない [start](テスト用のフェイクなど)。
     * 取消を見ない実装でも、呼び出し側([VoiceSession])は戻ったあとにもう一度確認し、取り消されていれば [stop] する。
     */
    fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit, isCurrent: () -> Boolean) = start(onPcm, onError)

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
    /** 開始の合図(挨拶・チャイム)を鳴らしている(段 G。アバターを talking にする。マイクはまだ閉じている) */
    val cuePlaying: Boolean = false,
    /**
     * このセッション(往復)の脳(201 の `brain`。段階 2b-2)。画面の「外へ出る」表記([DaidaiText.brainNotice])の元。作成の応答を受けてから、
     * 合図・マイクを開くより前のスナップショットから載る。旧ホストや作成前・作成失敗では null。往復ごとに決め、持ち越さない。
     */
    val brain: BrainInfo? = null,
    /**
     * セッションが準備中(PREPARING。作成の応答を待つ間・開始の合図の前後・残響の待ち・マイクを開く処理の間)。
     * 表示用の [state] は PREPARING を IDLE に畳むので、「作成の応答を受けてから録音が始まるまで」を [brain] とあわせて表記の対象にする(R15)。
     */
    val preparing: Boolean = false,
    /**
     * 「外へ出る」表記の描画の確認の印(段階 2b-2。R15)。脳が外へ出る区分(`egress != HOST`)の往復だけ 0 でない(往復ごとに新しい)。
     * 画面([DaidaiView])が表記を描き終えたら、この印を [VoiceSession.brainNoticeDrawn] に返す。返るまで、[VoiceSession] は録音を始めない。
     * 0 は確認が要らない(旧ホスト・`host`・作成前)。
     */
    val brainAck: Int = 0,
) {
    override fun toString() = "VoiceSnapshot(state=$state, transcript=${transcript.length}chars, reply=${reply.length}chars, " +
        "error=$error, mic=$micActive, active=$active, preparing=$preparing, brain=$brain)"
}

/** [VoiceSession] の通知。**セッションのスレッドで呼ばれる**ので、UI への反映は呼び出し側が main へ post する。 */
interface VoiceSessionListener {
    /** 表示が変わるたびに。同じ内容は続けて呼ばない */
    fun onSnapshot(snapshot: VoiceSnapshot)

    /**
     * マイクを開いた(録音を始めた)直後。T8 はここで開始音(`ToneGenerator`)を鳴らす。
     * 開始音と録音の順序の方針: マイクを先に開き、鳴らす間の [VoiceLimits.START_TONE_GUARD_MS] は録音を捨てる(送らない)。
     *
     * `autoStarted`: **マイクを開いたそのセッション**の起点(通知の自動切替なら true)。呼び出し側が可変の状態を後から読まずに済むよう、
     * 通知の時点の値を渡す([onEnded] の `autoStarted` と同じ値)。
     * `follow` / `tag`: そのセッションの [TurnRequest.follow] / [TurnRequest.tag](続きの開始音と歯止めの判断に使う。同じく通知の時点の値)。
     */
    fun onMicOpened(autoStarted: Boolean, follow: Boolean, tag: TurnTag)

    /**
     * [onMicOpened] の、合図の段の結果つきの版(段 G)。[VoiceSession] はこちらを呼ぶ。既定は 3 引数の版を呼ぶ(合図を知らない実装・試験のフェイク)。
     * 例外の扱いは [onMicOpened] と同じ(録音を続けず `mic_unavailable`)。
     */
    fun onMicOpened(autoStarted: Boolean, follow: Boolean, tag: TurnTag, cue: MicCue) = onMicOpened(autoStarted, follow, tag)

    /**
     * 開始の合図(挨拶か、挨拶が無いときのチャイム)を鳴らし始めた(段 G。セッションのスレッドで、1 セッションに 1 回だけ。マイクはまだ閉じている)。
     * `greeting`: 挨拶を鳴らし始めたなら、その [CueChoice]。チャイムから始めたなら null。挨拶に失敗して続けて鳴らすチャイムでは呼ばない。
     * 例外は握りつぶされる(合図は止めない)。
     */
    fun onCueStarted(autoStarted: Boolean, tag: TurnTag, greeting: CueChoice?) {}

    /**
     * 作成が成功して、201 に `conversation` があった(現在の世代の作成だけ。**`startSse` / マイクを開くより前**に、セッションのスレッドで 1 回)。
     * ハンドルの持ち主は呼び出し側で、[VoiceSession] は覚えない。マイクを開く前に取消された場合も、この通知は出ている。例外は握りつぶされる。
     * 古い世代の作成([VoiceSession] が `DELETE` と会話の終わりを送る)では呼ばれない。`info` の `toString` はハンドルを含めない。
     */
    fun onConversation(tag: TurnTag, info: ConversationInfo) {}

    /**
     * セッションが終わった(DONE・ERROR・取消のどれか)ときに**セッションごとに 1 回だけ**。ERROR から IDLE へ戻るときは呼ばない。
     * `reason`: DONE は "done"、取消は "cancelled"、ERROR はエラーコードの文字列(例 "no_speech"。`AutoSwitchPolicy.REASON_NO_SPEECH` と一致)。
     * ホストの未知のコードは `host_` + `[a-z0-9_]` だけに整えた文字列(本物のコードと衝突しない。例 "host_weird_code"、整えると空なら "host_unknown")。
     * 返答音声の失敗(`audio: null` に `audio_error` が付いた場合を含む)で DONE になるときは "reply_audio_failed"。
     * 続きの往復([TurnRequest.follow])の無発話は、ERROR を表示せず取消と同じ IDLE に終え、`reason` は "no_speech"、`outcome` は [TurnOutcome.ERROR]。
     *
     * `outcome`: 往復の結果(設計書(段階 2)§5.2。理由の文字列からは決めず、終わった経路から決める)。`tag`: [TurnRequest.tag]。
     *
     * 録音中(マイクを開いている間)にこのリスナーの [onMicOpened]・[onSnapshot] が例外を投げると、セッションは録音を続けず
     * `mic_unavailable` で終わる。終了処理中の通知の例外は無視される。
     */
    fun onEnded(autoStarted: Boolean, reason: String, outcome: TurnOutcome, tag: TurnTag)
}
