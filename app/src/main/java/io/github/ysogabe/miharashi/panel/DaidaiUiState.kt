package io.github.ysogabe.miharashi.panel

/**
 * 音声セッションの**表示用**の状態(設計書 §5.5)。B の描画と、`VoiceSession`(T6)が画面へ渡す状態の列挙。
 * T6 の状態機械は `PREPARING` / `DONE` を含む内部状態を別に持ち、画面へ渡す前にこの列挙へ変換する:
 * `PREPARING` → `IDLE` または `LISTENING`(マイクを開いて聞き取りを始めていれば `LISTENING`)、`DONE` → `IDLE`。
 * 内部状態をそのまま持ち込まないこと(この列挙に値を足すと、表示の表・ポーズの対応を全部直すことになる)。
 */
enum class VoiceState { IDLE, LISTENING, HEARING, WAITING, REPLYING, ERROR }

/**
 * 利用者向けのエラーコード(設計書 §5.5 の固定表)。表示の文言は [DaidaiText.errorMessage] がコードから引く。
 * ホストの `message` は表示に使わない(診断の 1 行にだけ出す)。
 */
enum class VoiceErrorCode(val code: String) {
    NOT_CONFIGURED("not_configured"),
    MIC_PERMISSION("mic_permission"),
    MIC_UNAVAILABLE("mic_unavailable"),
    MIC_SILENT("mic_silent"),
    HOST_UNREACHABLE("host_unreachable"),
    NO_SPEECH("no_speech"),
    ASR_FAILED("asr_failed"),
    TIMEOUT("timeout"),
    DAIDAI_UNAVAILABLE("daidai_unavailable"),
    REPLY_AUDIO_FAILED("reply_audio_failed"),
    SESSION_LOST("session_lost"),
    EXPIRED("expired"),
    BUSY("busy"),
    ASR_UNAVAILABLE("asr_unavailable");

    companion object {
        /** 固定表にないコード(ホストが返した未知の文字列を含む)は null。呼び出し側が汎用のエラーとして扱う */
        fun fromCode(code: String?): VoiceErrorCode? = entries.firstOrNull { it.code == code }
    }
}

/** B の全面に出す案内の種類。`NONE` なら通常の画面。 */
enum class SetupGuide { NONE, NOT_CONFIGURED, MIC_PERMISSION }

/**
 * B が描くものすべて(設計書 §4.1)。不変で、保存しない。作るのは `DaidaiController`(T5)と `VoiceSession`(T6)で、
 * UI スレッドから [DaidaiView.render] に渡す。
 *
 * 文字列(通知・認識結果・返答)は外部入力なので、[DaidaiView] が描く前に制御文字などを除いてプレーンテキストで描く。
 * `toString` に本文を含めない(logcat に出さないため)。
 */
data class DaidaiUiState(
    val voice: VoiceState = VoiceState.IDLE,
    /** 通知表示中のポーズ名(`daidai.avatar_pose`)。語彙にない名前は `idle` として扱う */
    val noticePose: String? = null,
    /** 最新の通知。null ならカードを出さず、「話しかけてください」を出す */
    val notice: DaidaiNotice? = null,
    /** 最新以外の通知の件数(「他 N 件」)。0 なら出さない */
    val moreNotices: Int = 0,
    /** 認識中・認識済みのテキスト */
    val transcript: String = "",
    /** 返答テキスト */
    val reply: String = "",
    /**
     * `voice == ERROR` のときのコード。null や固定表にない場合は汎用の文言になる。
     * `voice != ERROR` で `reply` が空でないときに入っていれば(返答音声の失敗)、返答の下に固定文言を併記する。
     */
    val error: VoiceErrorCode? = null,
    /** ホストの `message` など診断用の 1 行。画面の本文には出さない */
    val diagnostics: String? = null,
    /** 実際の `AudioRecord` の録音状態(`RECORDSTATE_RECORDING`)に連動させる値。セッションの状態とは別 */
    val micActive: Boolean = false,
    /** 設定が足りないときの案内 */
    val setup: SetupGuide = SetupGuide.NONE,
    /** 開始の合図(挨拶・チャイム)を鳴らしている(段 G)。アバターを talking にする。マイクはまだ閉じている(`micActive` は false のまま) */
    val cuePlaying: Boolean = false,
    /** 今の往復の脳(段階 2b-2)。[brainNotice] の元。null(旧ホスト・作成前)なら表記を出さない */
    val brain: BrainInfo? = null,
    /** セッションが準備中(作成の応答のあと〜録音が始まる前。合図の前後・残響の待ちを含む)。[brainNotice] の条件 */
    val preparing: Boolean = false,
    /**
     * 「外へ出る」表記の描画の確認の印([VoiceSnapshot.brainAck])。0 でないとき、[DaidaiView] は [brainNotice] を描き終えたら、この印を返す(録音を始める確認。R15)。
     * 外へ出ない脳(旧ホスト・`host`)は 0 で、何も返さない。
     */
    val brainAck: Int = 0,
) {
    /**
     * 作成の応答を受けてから録音が終わるまで(準備中・合図の再生中・聞き取り中・認識中・マイクが録音中)、途切れずに出す、どこへ送るかの表記(R15)。
     * それ以外(待機・応答待ち・返答中・エラー)と、脳が分からないとき(旧ホスト・作成前)は null。
     */
    val brainNotice: String?
        get() = if (preparing || voice == VoiceState.LISTENING || voice == VoiceState.HEARING || micActive || cuePlaying) DaidaiText.brainNotice(brain) else null

    /** アバターのポーズ(§5.5 の対応。通知のポーズは待機中のカード表示のときだけ使う。合図の再生中は talking) */
    val avatarPose: String get() = if (cuePlaying) DaidaiPose.TALKING else DaidaiText.pose(voice, noticePose, notice != null)

    /** 画面全体に出す案内。権限なしのエラーは、`setup` が空でも権限の案内にする */
    val guide: SetupGuide
        get() = when {
            setup != SetupGuide.NONE -> setup
            voice == VoiceState.ERROR && error == VoiceErrorCode.MIC_PERMISSION -> SetupGuide.MIC_PERMISSION
            else -> SetupGuide.NONE
        }

    override fun toString() = "DaidaiUiState(voice=$voice, pose=$avatarPose, notice=${notice != null}, more=$moreNotices, " +
        "transcript=${transcript.length}chars, reply=${reply.length}chars, error=$error, mic=$micActive, setup=$setup, brain=$brain)"

    companion object {
        /** 設定を読んだだけの状態。未設定なら案内、設定済みなら待機中 */
        fun forSettings(configured: Boolean) =
            DaidaiUiState(setup = if (configured) SetupGuide.NONE else SetupGuide.NOT_CONFIGURED)
    }
}

/** ブリッジの接続先とトークンが両方入っているか(どちらかが空なら音声機能は動かさない)。 */
fun DaidaiSettings.isConfigured(): Boolean = url.isNotEmpty() && token.isNotEmpty()
