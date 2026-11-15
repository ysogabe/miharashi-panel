package io.github.ysogabe.miharashi.panel

/**
 * 書記素クラスタ(利用者が 1 文字と見るまとまり)の境界(純 Kotlin。Android にも JVM の単体テストにも同じ結果を返す)。
 * `java.text.BreakIterator` は JDK 17 では国旗・肌色・ZWJ 結合の絵文字を分けてしまうので、折り返し・省略・上限処理に要る範囲を自前で持つ:
 * - 結合文字(濁点・アクセントなど。Mn / Me / Mc)、ZWNJ・ZWJ、異体字選択子
 * - 国旗(地域指示記号の対)
 * - 肌色修飾(U+1F3FB–1F3FF)、タグ(U+E0020–E007F。地域旗など)
 * - 絵文字の ZWJ 結合(絵文字 ZWJ 絵文字 …)
 * - CR LF は 1 つ。制御文字は単独
 * ハングルの字母(L・V・T)の結合は扱わない(完成形の音節はそのまま 1 文字)。
 */
object Graphemes {
    /** `start` から始まる 1 クラスタの終わり(含まない)。`start` は文字列の中の、サロゲートの途中でない位置 */
    fun next(s: CharSequence, start: Int): Int {
        val len = s.length
        require(start in 0 until len)
        val first = Character.codePointAt(s, start)
        var i = start + Character.charCount(first)
        if (first == '\r'.code && i < len && s[i] == '\n') return i + 1
        if (Character.isISOControl(first)) return i
        if (isRegionalIndicator(first) && i < len) {
            val second = Character.codePointAt(s, i)
            if (isRegionalIndicator(second)) i += Character.charCount(second)
        }
        val pictographicBase = isPictographic(first)
        while (i < len) {
            val c = Character.codePointAt(s, i)
            if (!isExtend(c)) break
            i += Character.charCount(c)
            if (c == ZWJ && pictographicBase && i < len) {
                val joined = Character.codePointAt(s, i)
                if (isPictographic(joined)) i += Character.charCount(joined)
            }
        }
        return i
    }

    /** 各クラスタの終わりの位置(昇順、最後は `s.length`)。空文字列なら空 */
    fun ends(s: CharSequence): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            i = next(s, i)
            out += i
        }
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtend(cp: Int): Boolean {
        if (cp == ZWJ || cp == 0x200C || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in 0xFE00..0xFE0F) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }

    private fun isPictographic(cp: Int): Boolean = when {
        isRegionalIndicator(cp) -> false
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp in 0x25A0..0x25FF -> true
        else -> cp == 0xA9 || cp == 0xAE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 ||
            cp == 0x2934 || cp == 0x2935 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
    }
}
