package io.github.ysogabe.miharashi.panel

/**
 * 脳(返答を作る側)が、どこまで送るか。作成の応答(`201`)の `brain.egress`(段階 2b-2。設計書(段階 2)§6.1・§6.6)。
 * [UNKNOWN] は、`brain` が辞書で来たのに `egress` が `host` / `lan` / `cloud` のどれでもない(欠けている・文字列でない)とき。
 * 「外へ出る」側に倒して表示する(出ないと決めつけない)。
 */
enum class BrainEgress {
    HOST,
    LAN,
    CLOUD,
    UNKNOWN,
    ;

    companion object {
        /** ホストの値。完全一致(大文字小文字・前後の空白は別物)の `host` / `lan` / `cloud` だけ。それ以外・文字列でない型は [UNKNOWN] */
        fun fromWire(v: Any?): BrainEgress = when (v as? String) {
            "host" -> HOST
            "lan" -> LAN
            "cloud" -> CLOUD
            else -> UNKNOWN
        }
    }
}

/**
 * 脳の名前。画面に出すのは既知の名前だけで、ホストの文字列は描かない([OTHER] は「その他」)。
 * 2b-2 で知っているのは `daidai`(だいだい)だけ。
 */
enum class BrainName {
    DAIDAI,
    OTHER,
    ;

    companion object {
        fun fromWire(v: Any?): BrainName = if ((v as? String) == "daidai") DAIDAI else OTHER
    }
}

/**
 * このセッションの脳(`201` の `brain`)。辞書で来たときだけ作る(無い・辞書でない = 旧ホストは null で、表示を出さない)。
 * 外部入力なので、生の文字列は持たない(区分と、既知の名前かどうかだけ)。`toString` もその 2 つだけ。
 */
data class BrainInfo(val name: BrainName, val egress: BrainEgress) {
    override fun toString() = "BrainInfo(name=$name, egress=$egress)"

    /** 外へ出ない(`host`)と分かっているか。false は「外へ出る・出るかもしれない」 */
    val staysOnHost: Boolean get() = egress == BrainEgress.HOST

    companion object {
        /** `brain` の値から。辞書でなければ null(旧ホスト) */
        fun parse(v: Any?): BrainInfo? {
            val o = v as? org.json.JSONObject ?: return null
            return BrainInfo(BrainName.fromWire(o.opt("name")), BrainEgress.fromWire(o.opt("egress")))
        }
    }
}
