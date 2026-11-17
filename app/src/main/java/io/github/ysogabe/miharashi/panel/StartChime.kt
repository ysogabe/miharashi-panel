package io.github.ysogabe.miharashi.panel

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 戻りの合図のチャイム(段 G。設計書(段階 2)§5.8.3)。挨拶を鳴らせないときに、挨拶と同じ [WavPlayer](`USAGE_MEDIA`。返答が聞こえている経路)で、
 * 挨拶と同じ順序(再生 → 300 ms → マイク)で鳴らす。アセットは使わず、アプリの中で PCM を作る。
 *
 * 2 音(高い音 → 低い音)、合わせて約 400 ms、PCM16 モノラル 24 kHz、44 バイトの標準ヘッダ。[WavValidator] を通る形。
 * 音の頭は 5 ms で立ち上げ、指数で減衰させる(クリックの音を出さない)。振幅は最大の 0.45 倍。音量は端末のメディアの音量に従う。
 */
object StartChime {
    const val SAMPLE_RATE = 24_000

    private const val FIRST_HZ = 880.0 // A5
    private const val SECOND_HZ = 659.25 // E5
    private const val FIRST_MS = 170
    private const val GAP_MS = 30
    private const val SECOND_MS = 200
    private const val AMPLITUDE = 0.45
    private const val ATTACK_MS = 5.0

    /** 全体の長さ(ミリ秒) */
    const val DURATION_MS = FIRST_MS + GAP_MS + SECOND_MS

    /** チャイムの WAV(ヘッダ込み)。呼ぶたびに新しい配列を返す(呼ぶ側が書き換えても次に影響しない) */
    fun wav(): ByteArray = PCM_WAV.copyOf()

    private val PCM_WAV: ByteArray by lazy { build() }

    private fun build(): ByteArray {
        val samples = ShortArray(SAMPLE_RATE * DURATION_MS / 1000)
        tone(samples, 0, FIRST_MS, FIRST_HZ)
        tone(samples, (FIRST_MS + GAP_MS) * SAMPLE_RATE / 1000, SECOND_MS, SECOND_HZ)
        return wrap(samples)
    }

    private fun tone(out: ShortArray, start: Int, ms: Int, hz: Double) {
        val n = ms * SAMPLE_RATE / 1000
        val attack = ATTACK_MS * SAMPLE_RATE / 1000
        for (i in 0 until n) {
            if (start + i >= out.size) return
            val t = i.toDouble() / SAMPLE_RATE
            val env = minOf(1.0, i / attack) * exp(-4.0 * i / n) * (1.0 - i.toDouble() / n) // 最後は 0 に落とす
            out[start + i] = (sin(2 * PI * hz * t) * env * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    /** PCM16 モノラルを 44 バイトの標準ヘッダで包む */
    internal fun wrap(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val b = ByteArray(44 + dataBytes)
        fun ascii(off: Int, s: String) = s.forEachIndexed { i, ch -> b[off + i] = ch.code.toByte() }
        fun u32(off: Int, v: Int) { for (i in 0..3) b[off + i] = (v ushr (8 * i)).toByte() }
        fun u16(off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte() }
        ascii(0, "RIFF"); u32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1); u32(24, sampleRate); u32(28, sampleRate * 2); u16(32, 2); u16(34, 16)
        ascii(36, "data"); u32(40, dataBytes)
        for ((i, s) in samples.withIndex()) u16(44 + i * 2, s.toInt())
        return b
    }
}
