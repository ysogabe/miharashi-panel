package io.github.ysogabe.miharashi.panel

import java.io.ByteArrayOutputStream

/** テスト用の WAV の組み立て。宣言(ヘッダ)と実バイトを別々に指定して、壊れた WAV も作れる。 */
object WavFixtures {
    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Long) { le16((v and 0xFFFF).toInt()); le16(((v shr 16) and 0xFFFF).toInt()) }
    private fun ByteArrayOutputStream.tag(t: String) = write(t.toByteArray(Charsets.US_ASCII))

    /** `fmt ` のチャンク(本文だけでなく見出しも) */
    fun fmt(
        format: Int = 1, channels: Int = 1, rate: Int = 16_000, bits: Int = 16,
        blockAlign: Int = channels * bits / 8, byteRate: Int = rate * blockAlign, extraBytes: Int = 0,
    ): ByteArray = ByteArrayOutputStream().apply {
        tag("fmt ")
        le32((16 + extraBytes).toLong())
        le16(format); le16(channels); le32(rate.toLong()); le32(byteRate.toLong()); le16(blockAlign); le16(bits)
        repeat(extraBytes) { write(0) }
        if (extraBytes % 2 == 1) write(0)
    }.toByteArray()

    /** 任意のチャンク(`LIST` など)。奇数サイズは 1 バイトのパディングを付ける。 */
    fun chunk(id: String, size: Int, pad: Boolean = true): ByteArray = ByteArrayOutputStream().apply {
        tag(id)
        le32(size.toLong())
        repeat(size) { write(7) }
        if (pad && size % 2 == 1) write(0)
    }.toByteArray()

    /**
     * WAV 全体。`dataBytes` は実際に付けるデータの長さ、`declaredData` はヘッダに書く `data` のサイズ(null なら `dataBytes`)。
     * `riffSize` が null なら正しい値。`before` は `fmt ` と `data` の間に入れるチャンク、`fmtChunk` は差し替え、`trailing` は末尾の余り。
     */
    fun wav(
        sampleRate: Int = 16_000, dataBytes: Int = 3_200, declaredData: Long? = null, riffSize: Long? = null,
        before: ByteArray = ByteArray(0), fmtChunk: ByteArray? = null, trailing: ByteArray = ByteArray(0), magic: String = "RIFF", wave: String = "WAVE",
        fill: Int = 0x11,
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            tag(wave)
            write(fmtChunk ?: fmt(rate = sampleRate))
            write(before)
            tag("data")
            le32(declaredData ?: dataBytes.toLong())
            repeat(dataBytes) { write(if (it % 2 == 0) fill else 0) }
            write(trailing)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            tag(magic)
            le32(riffSize ?: body.size.toLong())
            write(body)
        }.toByteArray()
    }

    /** 正常な WAV(24 kHz、`ms` ミリ秒分) */
    fun valid(ms: Int = 500, rate: Int = 24_000): ByteArray = wav(sampleRate = rate, dataBytes = (rate * 2L * ms / 1000).toInt() / 2 * 2)
}
