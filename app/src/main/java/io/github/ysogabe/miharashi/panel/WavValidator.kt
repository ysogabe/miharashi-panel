package io.github.ysogabe.miharashi.panel

import java.io.ByteArrayInputStream
import java.io.InputStream

/** WAV の検証に落ちた理由。メッセージには入力の内容を含めない。 */
enum class WavProblem {
    /** 1 バイトも読めなかった */
    EMPTY,

    /** 先頭が `RIFF` でない */
    NOT_RIFF,

    /** 3〜4 バイト目以降が `WAVE` でない */
    NOT_WAVE,

    /** `fmt ` が無い・短い・重複・矛盾(`block_align` など) */
    BAD_FMT,

    /** PCM でない(圧縮・浮動小数)・16bit でない・mono でない */
    UNSUPPORTED_FORMAT,

    /** サンプルレートが 16〜48 kHz の外 */
    SAMPLE_RATE,

    /** `data` が無い・空 */
    NO_DATA,

    /** `data` の宣言と実際のバイト数が合わない(足りない・余る・奇数) */
    SIZE_MISMATCH,

    /** ヘッダの途中で終わった */
    TRUNCATED,

    /** 10 MB を超える(または超える宣言) */
    TOO_LARGE,

    /** 再生時間が 120 秒を超える */
    TOO_LONG,

    /** `data` の前のチャンクが大きすぎる */
    HEADER_TOO_LARGE,
}

/** WAV の検証失敗。[reason] だけを持ち、入力のバイト列は持たない。 */
class WavFormatException(val reason: WavProblem) : Exception("invalid wav: $reason")

/** 検証済み WAV の要約。[dataOffset] から [dataBytes] バイトが PCM16 mono のサンプル。 */
data class WavInfo(val sampleRate: Int, val dataOffset: Int, val dataBytes: Int) {
    val durationMs: Long get() = dataBytes.toLong() * 1000 / (sampleRate.toLong() * 2)
}

/**
 * 返答音声(WAV)の検証(設計書 §5.2.4, §7)。純 Kotlin。OS のメディアパーサに細工されたデータを渡さないため、Panel 自身が確かめる。
 *
 * - RIFF/WAVE、`fmt ` が PCM(1)・16bit・mono・サンプルレート 16〜48 kHz。`block_align` は 2。`byte_rate` は信用しない(見ない)。
 * - `data` の前の未知のチャンク(`LIST` `fact` など)は読み飛ばす(奇数サイズは 1 バイトのパディング)。ただし合計 64 KB まで。
 * - `data` のサイズは符号なしで見る。**10 MB・120 秒を超える宣言は、本文を読む前に**拒否する(`0xFFFFFFFF` のストリーム WAV も)。
 *   読みながらも 10 MB を超えたら打ち切る(`Content-Length` を信用しない)。
 * - `data` の宣言と実バイト数は**一致**させる(足りなければ切れた WAV、余れば末尾に何かが付いた WAV として拒否)。
 *   外側の RIFF のサイズは見ない(エンコーダが嘘を書くことがあるため)。
 * - 戻りはヘッダ込みの WAV 全体(プレーヤーが WAV を受け取る)。ファイルには書かない。
 */
object WavValidator {
    const val MAX_BYTES = VoiceLimits.REPLY_AUDIO_MAX_BYTES
    const val MAX_DURATION_MS = VoiceLimits.REPLY_AUDIO_MAX_MS
    const val MIN_SAMPLE_RATE = 16_000
    const val MAX_SAMPLE_RATE = 48_000

    /** `data` より前のチャンクを含むヘッダの上限 */
    const val MAX_HEADER_BYTES = 64 * 1024

    /** 読み出しの塊の大きさ(読む量が上限の判定を細かくずらさないための最大) */
    private const val READ_STEP = 16 * 1024

    private class Header(val sampleRate: Int, val dataOffset: Int, val dataSize: Int)

    /** メモリ上の WAV を検証する。 */
    @Throws(WavFormatException::class)
    fun validate(wav: ByteArray): WavInfo {
        if (wav.size > MAX_BYTES) throw WavFormatException(WavProblem.TOO_LARGE)
        return readInternal(ByteArrayInputStream(wav)).second
    }

    /**
     * `input` を読みながら検証し、ヘッダ込みの WAV 全体を返す。上限を超えると読むのをやめて例外にする(残りは読まない)。
     * 時間の上限は呼び出し側のウォッチドッグが見る。`input` は閉じない。
     */
    @Throws(WavFormatException::class, java.io.IOException::class)
    fun read(input: InputStream): ByteArray = readInternal(input).first

    private fun readInternal(input: InputStream): Pair<ByteArray, WavInfo> {
        var buf = ByteArray(READ_STEP)
        var len = 0
        var header: Header? = null
        while (true) {
            // 読んでよい上限: ヘッダが分かるまではヘッダの上限、分かったら宣言された末尾の 1 バイト先(余りの検出)
            val limit = header?.let { it.dataOffset + it.dataSize + 1 } ?: (MAX_HEADER_BYTES + 12 + 8)
            if (limit > buf.size && len == buf.size) buf = buf.copyOf(minOf(limit, maxOf(buf.size * 2, len + READ_STEP)))
            val want = minOf(buf.size, limit) - len
            val n = if (want > 0) input.read(buf, len, want) else -1
            if (n < 0) {
                val h = header ?: run {
                    if (len == 0) throw WavFormatException(WavProblem.EMPTY)
                    throw WavFormatException(WavProblem.TRUNCATED)
                }
                if (len != h.dataOffset + h.dataSize) throw WavFormatException(WavProblem.SIZE_MISMATCH)
                return buf.copyOf(len) to WavInfo(h.sampleRate, h.dataOffset, h.dataSize)
            }
            if (n == 0) continue
            len += n
            if (len > MAX_BYTES) throw WavFormatException(WavProblem.TOO_LARGE)
            if (header == null) {
                header = parseHeader(buf, len)
                if (header != null) {
                    // データの大きさの宣言を、読む前に確かめる
                    if (header.dataSize <= 0) throw WavFormatException(WavProblem.NO_DATA)
                    if (header.dataSize % 2 != 0) throw WavFormatException(WavProblem.SIZE_MISMATCH)
                    val total = header.dataOffset.toLong() + header.dataSize
                    if (total > MAX_BYTES) throw WavFormatException(WavProblem.TOO_LARGE)
                    if (header.dataSize.toLong() * 1000 / (header.sampleRate.toLong() * 2) > MAX_DURATION_MS) {
                        throw WavFormatException(WavProblem.TOO_LONG)
                    }
                }
            }
            if (header != null && len > header.dataOffset + header.dataSize) throw WavFormatException(WavProblem.SIZE_MISMATCH)
        }
    }

    /** `buf` の先頭 `len` バイトからヘッダを読む。まだ足りなければ null。不正なら例外。`data` の本文の手前までを見る。 */
    private fun parseHeader(buf: ByteArray, len: Int): Header? {
        if (len >= 4 && !tag(buf, 0, "RIFF")) throw WavFormatException(WavProblem.NOT_RIFF)
        if (len >= 12 && !tag(buf, 8, "WAVE")) throw WavFormatException(WavProblem.NOT_WAVE)
        if (len < 12) return null
        var pos = 12L
        var sampleRate = 0
        var fmtSeen = false
        while (true) {
            if (len < pos + 8) return null
            val p = pos.toInt()
            val size = u32(buf, p + 4)
            if (tag(buf, p, "data")) {
                if (!fmtSeen) throw WavFormatException(WavProblem.BAD_FMT)
                // 4 GB 級の宣言は Int に収まらないので、ここで上限超過として扱う
                if (size > MAX_BYTES) throw WavFormatException(WavProblem.TOO_LARGE)
                return Header(sampleRate, p + 8, size.toInt())
            }
            val padded = size + (size and 1L)
            if (pos + 8 + padded > MAX_HEADER_BYTES) throw WavFormatException(WavProblem.HEADER_TOO_LARGE)
            if (tag(buf, p, "fmt ")) {
                if (fmtSeen || size < 16) throw WavFormatException(WavProblem.BAD_FMT)
                if (len < pos + 8 + 16) return null
                val b = p + 8
                val format = u16(buf, b)
                val channels = u16(buf, b + 2)
                val rate = u32(buf, b + 4)
                val blockAlign = u16(buf, b + 12)
                val bits = u16(buf, b + 14)
                if (format != 1 || channels != 1 || bits != 16) throw WavFormatException(WavProblem.UNSUPPORTED_FORMAT)
                if (blockAlign != 2) throw WavFormatException(WavProblem.BAD_FMT)
                if (rate < MIN_SAMPLE_RATE || rate > MAX_SAMPLE_RATE) throw WavFormatException(WavProblem.SAMPLE_RATE)
                sampleRate = rate.toInt()
                fmtSeen = true
            }
            pos += 8 + padded
        }
    }

    private fun tag(b: ByteArray, off: Int, t: String): Boolean =
        b[off] == t[0].code.toByte() && b[off + 1] == t[1].code.toByte() && b[off + 2] == t[2].code.toByte() && b[off + 3] == t[3].code.toByte()

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)
}
