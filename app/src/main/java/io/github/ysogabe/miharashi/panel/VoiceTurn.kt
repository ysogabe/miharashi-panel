package io.github.ysogabe.miharashi.panel

/**
 * 往復(= 1 セッション)の型(段階 2a-P。設計書(段階 2)§5.1, §5.2, §6.1)。複数往復は「往復ごとに新しいセッション」で作り、
 * [VoiceSession] は 1 往復だけを受け持つ(状態は後退しない)。会話(世代・往復番号・続けるかの判定)は呼ぶ側([DaidaiVoice])が持つ。
 * ここの型は、呼ぶ側が付ける印と、往復の結果、ホストとの会話の受け渡しだけ。
 *
 * ハンドル(会話を結ぶ秘密に準ずる文字列)は、`toString`・例外のメッセージ・診断の文字列に入れない。
 */

/** 呼ぶ側が往復に付ける不変の印。[VoiceSession] は中身を見ず、通知([VoiceSessionListener])にそのまま返す。 */
data class TurnTag(
    /** 会話の世代(単調に増える。古い世代の通知を捨てる照合に使う) */
    val conversationGen: Long = 0L,
    /** 会話の中での往復番号(1 往復目から) */
    val turn: Int = 0,
) {
    companion object {
        /** 印なし(会話を持たない呼び方。今の `start(reason, noticeId, autoStarted)`) */
        val NONE = TurnTag()
    }
}

/**
 * 作成の本文の `conversation`(省略できる)。`SessionRequest.conversation` が null なら本文に書かない(旧 Panel と同じ)。
 * 非 null なら、`handle` が null で `{}`(新しい会話)、あれば `{"handle": …}`(続き)。JSON の null は送らない。
 * `toString` はハンドルを含めず、有無だけ。
 */
data class ConversationParam(val handle: String? = null) {
    override fun toString() = "ConversationParam(handle=${if (handle == null) "none" else "set"})"
}

/**
 * 作成の応答(`201`)の `conversation`。`handle` は [VoiceLimits.CONVERSATION_HANDLE_REGEX] に完全一致したものだけ(外れたら null)。
 * `context`: ホストが文脈を付けたか(boolean 以外は null)。`continueAllowed`: JSON の `continue` が boolean の false のときだけ false。
 * `toString` はハンドルを含めず、有無だけ。
 */
data class ConversationInfo(val handle: String?, val context: Boolean?, val continueAllowed: Boolean) {
    override fun toString() =
        "ConversationInfo(handle=${if (handle == null) "none" else "set"}, context=$context, continue=$continueAllowed)"
}

/**
 * 往復の要求。`reason` / `noticeId` は会話の最初の値を続きの往復でもそのまま送る(ホストは `swipe` / `notification` 以外を 400 にする)。
 * `follow`: 続きの往復か(続きの無発話は ERROR にせず IDLE に終える)。`noSpeechS`: 作成の本文の `limits.no_speech_s`
 * (続きの往復は [VoiceLimits.FOLLOW_NO_SPEECH_S])。`toString` は `conversation` の中身(ハンドル)を含めない。
 */
data class TurnRequest(
    val reason: StartReason,
    val noticeId: String?,
    val autoStarted: Boolean,
    val conversation: ConversationParam? = null,
    val noSpeechS: Long = VoiceLimits.NO_SPEECH_DEFAULT_S,
    val follow: Boolean = false,
    val tag: TurnTag = TurnTag.NONE,
    /**
     * 開始の合図(段 G。設計書(段階 2)§5.8)。非 null なら、作成のあとマイクを閉じたまま合図を鳴らし、鳴り終えてからマイクを開く。
     * null なら合図の段を置かない(続きの往復・旧来の呼び方。マイクを開いた直後の開始音と 400 ms の破棄のまま)。続きの往復([follow])では無視する。
     */
    val cue: StartCuePlan? = null,
)

/**
 * 開始の合図の段の中身(段 G)。[greeting] は検証済みの挨拶の WAV(取得できていなければ null)、[chime] は戻りのチャイム([StartChime])。
 * [choice] は選んだ挨拶([greeting] が null でも、何を選んだかを持つ)。WAV を持つので `data class` にしない(`toString` に中身を出さない)。
 */
class StartCuePlan(val greeting: ByteArray?, val chime: ByteArray, val choice: CueChoice?) {
    override fun toString() = "StartCuePlan(greeting=${if (greeting == null) "none" else "set"}, choice=${choice?.name})"
}

/** マイクを開いたときの、合図の段の結果([VoiceSessionListener.onMicOpened] に渡す) */
enum class MicCue {
    /** 合図の段が無い(続きの往復・旧来の呼び方)。今までどおり開始音を鳴らす */
    NONE,

    /** 挨拶かチャイムを最後まで鳴らした。開始音は鳴らさない */
    HEARD,

    /** 挨拶もチャイムも鳴らせなかった。最後の手段として開始音(ビープ)を鳴らす */
    FAILED,
}

/**
 * 往復の結果(設計書(段階 2)§5.2 の表)。[canContinue] は結果だけから決まる「続けてよいか」で、会話の上限・在席・画面の状態などの
 * ほかの条件は呼ぶ側が見る。`TAPPED` は 2a-T(再生中のタップ)で使う値で、2a-P の [VoiceSession] は作らない。
 */
enum class TurnOutcome(val canContinue: Boolean) {
    /** 返答の音声を最後まで再生した */
    PLAYED(true),

    /** 再生中のタップで止めた(2a-T) */
    TAPPED(true),

    /** `audio: null`(`audio_error` の有無を問わない)で、文字だけを出した */
    TEXT_ONLY(false),

    /** 返答の音声の宣言の拒否・取得の失敗・取得の期限・大きさ・再生の失敗・`play` の例外 */
    PLAYBACK_FAILED(false),

    /** 再生の期限切れ */
    PLAYBACK_TIMEOUT(false),

    /** REPLYING の間にセッション全体の期限が切れた */
    SESSION_TIMEOUT(false),

    /** 終端 `error`(`no_speech` を含む)・作成の失敗・そのほかの ERROR */
    ERROR(false),

    /** 取消 */
    CANCELLED(false),
}
