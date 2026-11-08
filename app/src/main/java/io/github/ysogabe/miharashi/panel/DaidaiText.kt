package io.github.ysogabe.miharashi.panel

/** アバターのポーズの語彙(設計書 §5.5、O-6)。ホストの `avatar_pose` と揃える。 */
object DaidaiPose {
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val WORKING = "working"
    const val TALKING = "talking"

    val ALL = listOf(IDLE, LISTENING, WORKING, TALKING)

    /** 語彙にない名前(null・空・大文字小文字違いを含む)は `idle`。 */
    fun normalize(name: String?): String = if (name != null && name in ALL) name else IDLE
}

/** B の表示文言と、状態からの対応(純 Kotlin。Android に依存しない)。 */
object DaidaiText {
    const val HINT = "話しかけてください"
    const val GENERIC_ERROR = "うまくいきませんでした。もう一度お試しください"

    /** 認識結果・返答などの長さの上限(コードポイント。書記素クラスタの途中では切らない)。これ以上は読み捨てる */
    const val MAX_TRANSCRIPT = 300
    const val MAX_REPLY = 2000

    fun stateLabel(state: VoiceState): String = when (state) {
        VoiceState.IDLE -> "待機中"
        VoiceState.LISTENING, VoiceState.HEARING -> "聞き取り中"
        VoiceState.WAITING -> "応答待ち"
        VoiceState.REPLYING -> "返答中"
        VoiceState.ERROR -> "エラー"
    }

    /**
     * 状態に対するアバターのポーズ。通知のポーズ(`noticePose`)は、待機中に通知カードを出しているときだけ使う。
     * 音声セッションが動いている間は、セッションの状態を優先する。
     */
    fun pose(state: VoiceState, noticePose: String?, noticeShown: Boolean): String = when (state) {
        VoiceState.IDLE -> if (noticeShown && noticePose != null) DaidaiPose.normalize(noticePose) else DaidaiPose.IDLE
        VoiceState.LISTENING, VoiceState.HEARING -> DaidaiPose.LISTENING
        VoiceState.WAITING -> DaidaiPose.WORKING
        VoiceState.REPLYING -> DaidaiPose.TALKING
        VoiceState.ERROR -> DaidaiPose.IDLE
    }

    private val ERROR_MESSAGES: Map<VoiceErrorCode, String> = mapOf(
        VoiceErrorCode.NOT_CONFIGURED to "ブリッジが未設定です。設定で接続先とトークンを入れてください",
        VoiceErrorCode.MIC_PERMISSION to "マイクの権限がありません。adb で権限を許可してください",
        VoiceErrorCode.MIC_UNAVAILABLE to "マイクを使えません。ほかのアプリが使っているかもしれません",
        VoiceErrorCode.MIC_SILENT to "マイクから音が来ません。本体のマイクオフスイッチを確認してください",
        VoiceErrorCode.HOST_UNREACHABLE to "ブリッジに接続できません。ホストと Wi-Fi を確認してください",
        VoiceErrorCode.NO_SPEECH to "声が聞こえませんでした。もう一度話しかけてください",
        VoiceErrorCode.ASR_FAILED to "聞き取りに失敗しました。もう一度お試しください",
        VoiceErrorCode.TIMEOUT to "応答に時間がかかりすぎました。もう一度お試しください",
        VoiceErrorCode.DAIDAI_UNAVAILABLE to "だいだいに接続できません。しばらくしてからお試しください",
        VoiceErrorCode.REPLY_AUDIO_FAILED to "返答の音声を再生できませんでした",
        VoiceErrorCode.SESSION_LOST to "接続が途切れました。もう一度お試しください",
        VoiceErrorCode.EXPIRED to "会話の期限が切れました。もう一度話しかけてください",
        VoiceErrorCode.BUSY to "ほかの会話を処理中です。しばらくしてからお試しください",
        VoiceErrorCode.ASR_UNAVAILABLE to "音声認識を使えません。ホストの設定を確認してください",
    )

    /** コードから引く利用者向けの固定文言。ホストが返した文字列は使わない。コードがない・未知なら汎用の文言 */
    fun errorMessage(code: VoiceErrorCode?): String = code?.let { ERROR_MESSAGES[it] } ?: GENERIC_ERROR

    /** 案内の見出し */
    fun guideTitle(guide: SetupGuide): String = when (guide) {
        SetupGuide.NONE -> ""
        SetupGuide.NOT_CONFIGURED -> "ブリッジが未設定です"
        SetupGuide.MIC_PERMISSION -> "マイクの権限がありません"
    }

    /** 案内の本文(改行つき)。設定画面での手順や adb のコマンド */
    fun guideBody(guide: SetupGuide): String = when (guide) {
        SetupGuide.NONE -> ""
        SetupGuide.NOT_CONFIGURED -> "1. 右へスワイプして操作盤に戻り、左下の「設定」を開く\n" +
            "2. だいだいの接続先 URL とトークンを入れて保存する\n" +
            "3. もう一度この画面へスワイプする"
        // コマンドは文字列の上では必ず 1 行。画面の幅で折り返されても、コピーして実行できるのは 1 行のまま
        SetupGuide.MIC_PERMISSION -> "パソコンから adb で権限を許可してください:\n" +
            "adb shell pm grant $PACKAGE_NAME android.permission.RECORD_AUDIO"
    }

    const val PACKAGE_NAME = "io.github.ysogabe.miharashi.panel"

    /**
     * 外部入力のテキストを描画用にそろえる。制御文字・双方向制御文字・ゼロ幅の文字・対になっていないサロゲートを除き、
     * 前後の空白を落として `max` コードポイントまでにする(クラスタの途中で切れるなら、その手前まで)(通知を読む時点でも同じ処理をするので、描画前の二重の防御)。
     * 改行は `keepNewlines` なら `\n` 1 つにそろえ、そうでなければ除く。HTML としては解釈しない。
     */
    fun clean(raw: String?, max: Int, keepNewlines: Boolean): String = DaidaiInfo.sanitize(raw, max, keepNewlines)
}

/**
 * 折り返し・縮小・省略(純 Kotlin。書記素クラスタ単位で、絵文字などを途中で分けない)。文字の幅は呼び出し側が渡す `measure` で測る(View では `Paint.measureText`)。
 * 長い入力でも、`maxLines` 行を超えたところで読むのをやめるので、入力の長さに比例して重くならない。
 */
object TextFitter {
    const val ELLIPSIS = "…"

    class Wrapped(val lines: List<String>, val more: Boolean)

    class Fit(val size: Float, val lines: List<String>, val truncated: Boolean)

    /**
     * `maxWidth` に収まるよう貪欲に折り返す。`\n` で改行する。英数字の単語は、行中に空白があればそこで折る。
     * 1 文字が幅を超えても、その文字は 1 行に置く(無限に空行を作らない)。
     * `more` は、`maxLines` 行に入りきらなかった残りがあること。
     */
    fun wrap(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): Wrapped {
        if (maxLines <= 0) return Wrapped(emptyList(), text.isNotEmpty())
        val lines = ArrayList<String>(maxLines)
        val cur = StringBuilder()
        var more = false

        fun flush(): Boolean {
            if (lines.size >= maxLines) {
                more = true
                return false
            }
            lines += cur.toString().trimEnd(' ')
            cur.setLength(0)
            return true
        }

        var i = 0
        while (i < text.length) {
            val end = Graphemes.next(text, i)
            val g = text.substring(i, end)
            i = end
            if (g == "\n" || g == "\r\n") {
                if (!flush()) break
                continue
            }
            if (g == " " && cur.isEmpty()) continue // 折り返し直後の行頭の空白
            val before = cur.length
            cur.append(g)
            if (before > 0 && measure(cur.toString()) > maxWidth) {
                cur.setLength(before)
                var carry = ""
                if (g.length == 1 && isWordChar(g[0].code) && isWordChar(cur[before - 1].code)) {
                    val sp = cur.lastIndexOf(" ")
                    if (sp >= 0) {
                        carry = cur.substring(sp + 1)
                        cur.setLength(sp)
                    }
                }
                if (!flush()) break
                cur.append(carry)
                if (g != " " || cur.isNotEmpty()) cur.append(g)
            }
        }
        if (!more && cur.isNotEmpty()) {
            if (lines.size >= maxLines) more = true else lines += cur.toString().trimEnd(' ')
        }
        return Wrapped(lines, more)
    }

    private fun isWordChar(cp: Int) = cp < 0x80 && Character.isLetterOrDigit(cp)

    /**
     * `sizes`(大きい順)を試し、`maxLines` 行に収まる最初の大きさで返す。どれでも収まらなければ、
     * 最後(最小)の大きさで `maxLines` 行にして、最終行の末尾を「…」にする。
     */
    fun fit(text: String, maxWidth: Float, maxLines: Int, sizes: List<Float>, measure: (Float, String) -> Float): Fit {
        require(sizes.isNotEmpty())
        for (size in sizes) {
            val w = wrap(text, maxWidth, maxLines) { measure(size, it) }
            if (!w.more) return Fit(size, w.lines, false)
        }
        val size = sizes.last()
        val w = wrap(text, maxWidth, maxLines) { measure(size, it) }
        val lines = w.lines.toMutableList()
        if (lines.isEmpty()) return Fit(size, lines, false)
        lines[lines.lastIndex] = ellipsize(lines.last(), maxWidth, forced = true) { measure(size, it) }
        return Fit(size, lines, true)
    }

    /** 1 行に収める。収まらなければ末尾を削って「…」をつける。`forced` なら収まっていても「…」をつける */
    fun ellipsize(line: String, maxWidth: Float, forced: Boolean = false, measure: (String) -> Float): String {
        if (!forced && measure(line) <= maxWidth) return line
        val s = line.trimEnd()
        val ends = Graphemes.ends(s) // クラスタ(国旗・肌色・ZWJ の絵文字など)の途中では切らない
        var k = ends.size
        while (k > 0 && measure(s.substring(0, ends[k - 1]) + ELLIPSIS) > maxWidth) k--
        return (if (k == 0) "" else s.substring(0, ends[k - 1])) + ELLIPSIS
    }
}
