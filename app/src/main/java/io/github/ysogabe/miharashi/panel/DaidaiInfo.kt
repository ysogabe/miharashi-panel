package io.github.ysogabe.miharashi.panel

import org.json.JSONObject

/** 通知の優先度。`low` は自動切替しない(設計書 §4.4)。 */
enum class NoticePriority { NORMAL, LOW }

/**
 * だいだいの通知 1 件(設計書 §5.3)。外部入力なので、`id` は文字種を、`title` / `body` は長さと文字を [DaidaiInfo.parse] で絞ってある。
 * 文字列はプレーンテキストとして描画すること(HTML として解釈しない)。
 */
data class DaidaiNotice(
    val id: String,
    /** 最大 40 文字、改行なし */
    val title: String,
    /** 最大 200 文字。改行は `\n` だけ残る */
    val body: String,
    /** ホストが計算した、通知からの経過秒(応答生成時の値) */
    val ageS: Long,
    val priority: NoticePriority = NoticePriority.NORMAL,
) {
    /** 通知の本文を logcat に出さないため、`toString` に title / body を含めない。 */
    override fun toString() = "DaidaiNotice(id=$id, ageS=$ageS, priority=$priority)"
}

/**
 * status の任意の `daidai` ブロック(設計書 §5.3)。
 * `rev` はホストが付けるスナップショットの版番号で、ホストが持たない・不正なときは null(その扱いは使う側が決める)。
 */
data class DaidaiInfo(
    val rev: Long?,
    /** 新しい順、最大 [MAX_NOTICES] 件 */
    val notices: List<DaidaiNotice>,
    /** ホストが数えた未読件数。不正なら null。`ack` が true のときだけ採用する */
    val unseen: Int?,
    /** ホストが既読を管理するか(省略は false) */
    val ack: Boolean,
    /** 通知表示中のポーズ名(文字種を制限した短い文字列)。未知の名前の解釈は使う側 */
    val avatarPose: String?,
) {
    companion object {
        const val MAX_NOTICES = 5
        const val MAX_TITLE = 40
        const val MAX_BODY = 200
        const val MAX_POSE = 32

        private val ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
        private val POSE = Regex("^[A-Za-z0-9_-]{1,$MAX_POSE}$")

        /**
         * `daidai` の値を読む。オブジェクトでない・壊れているときは null(status 全体の解釈は失敗させない)。
         * 知らない項目は無視する。不正な通知(id が規定外、`age_s` が非負整数でない、title も body も空)は捨てる。
         * 通知の本文などは例外メッセージにも出さない。
         */
        fun parse(value: Any?): DaidaiInfo? {
            val o = value as? JSONObject ?: return null
            return try {
                DaidaiInfo(
                    rev = o.nonNegativeLong("rev"),
                    notices = parseNotices(o.opt("notices")),
                    unseen = o.nonNegativeLong("unseen")?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                    ack = o.opt("ack") == true,
                    avatarPose = (o.opt("avatar_pose") as? String)?.takeIf { POSE.matches(it) },
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseNotices(value: Any?): List<DaidaiNotice> {
            val arr = value as? org.json.JSONArray ?: return emptyList()
            val out = ArrayList<DaidaiNotice>(MAX_NOTICES)
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_NOTICES) break
                val n = arr.opt(i) as? JSONObject ?: continue
                val id = (n.opt("id") as? String)?.takeIf { ID.matches(it) } ?: continue
                if (out.any { it.id == id }) continue
                val age = n.nonNegativeLong("age_s") ?: continue
                val title = sanitize(n.opt("title") as? String, MAX_TITLE, keepNewlines = false)
                val body = sanitize(n.opt("body") as? String, MAX_BODY, keepNewlines = true)
                if (title.isEmpty() && body.isEmpty()) continue
                val priority = if (n.opt("priority") == "low") NoticePriority.LOW else NoticePriority.NORMAL
                out += DaidaiNotice(id, title, body, age, priority)
            }
            return out
        }

        private fun JSONObject.nonNegativeLong(key: String): Long? =
            when (val v = opt(key)) {
                is Int -> v.toLong()
                is Long -> v
                else -> null
            }?.takeIf { it >= 0 }

        /**
         * 制御文字・双方向制御文字・ゼロ幅の文字・対になっていないサロゲートを除き、前後の空白を落として、`max` 文字(コードポイント)までにする。
         * 改行(CR・LF・CRLF・NEL・LS・PS)は `keepNewlines` なら `\n` 1 つにし、そうでなければ除く。入力が巨大でも `max` 文字で読むのをやめる。
         */
        internal fun sanitize(raw: String?, max: Int, keepNewlines: Boolean): String {
            if (raw == null) return ""
            val sb = StringBuilder()
            var count = 0
            var i = 0
            while (i < raw.length && count < max) {
                val cp = raw.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp == '\r'.code && i < raw.length && raw[i] == '\n' -> continue // CRLF は次の LF で 1 回だけ数える
                    cp == '\r'.code || cp == '\n'.code || cp == 0x85 || cp == 0x2028 || cp == 0x2029 -> {
                        if (!keepNewlines || sb.isEmpty()) continue
                        sb.append('\n')
                        count++
                    }
                    Character.isISOControl(cp) || isInvisibleFormat(cp) || cp in 0xD800..0xDFFF -> continue
                    sb.isEmpty() && Character.isWhitespace(cp) -> continue
                    else -> {
                        sb.appendCodePoint(cp)
                        count++
                    }
                }
            }
            return sb.toString().trimEnd { it.isWhitespace() }
        }

        /** 双方向制御(U+061C, U+200E/F, U+202A–202E, U+2066–2069)とゼロ幅(U+200B, U+2060, U+FEFF)。 */
        private fun isInvisibleFormat(cp: Int) =
            cp == 0x061C || cp == 0x200B || cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E ||
                cp == 0x2060 || cp in 0x2066..0x2069 || cp == 0xFEFF
    }
}
