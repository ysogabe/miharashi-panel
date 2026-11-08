package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** 返答音声(WAV)の検証(設計書 §5.2.4、S15)。 */
class WavValidatorTest {
    private fun reject(expected: WavProblem, bytes: ByteArray) {
        try {
            WavValidator.validate(bytes)
            fail("拒否されるはずが通った: $expected")
        } catch (e: WavFormatException) {
            assertEquals(expected, e.reason)
        }
        // 読みながらの検証でも同じ理由
        try {
            WavValidator.read(ByteArrayInputStream(bytes))
            fail("read でも拒否されるはず: $expected")
        } catch (e: WavFormatException) {
            assertEquals(expected, e.reason)
        }
    }

    @Test fun validWavIsAcceptedAndReturnedWholeWithHeader() {
        val wav = WavFixtures.valid(ms = 500, rate = 24_000)
        val info = WavValidator.validate(wav)
        assertEquals(24_000, info.sampleRate)
        assertEquals(44, info.dataOffset)
        assertEquals(24_000, info.dataBytes) // 500 ms × 24 kHz × 2 バイト = 24,000
        assertEquals(500, info.durationMs)
        assertArrayEquals("ヘッダ込みの全体をそのまま返す", wav, WavValidator.read(ByteArrayInputStream(wav)))
    }

    @Test fun sampleRateBoundsAreInclusive() {
        for (rate in listOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
            assertEquals(rate, WavValidator.validate(WavFixtures.wav(sampleRate = rate)).sampleRate)
        }
        reject(WavProblem.SAMPLE_RATE, WavFixtures.wav(sampleRate = 15_999))
        reject(WavProblem.SAMPLE_RATE, WavFixtures.wav(sampleRate = 48_001))
        reject(WavProblem.SAMPLE_RATE, WavFixtures.wav(sampleRate = 8_000))
        reject(WavProblem.SAMPLE_RATE, WavFixtures.wav(sampleRate = 96_000))
        reject(WavProblem.SAMPLE_RATE, WavFixtures.wav(fmtChunk = WavFixtures.fmt(rate = 0)))
    }

    @Test fun notRiffOrNotWaveIsRejected() {
        reject(WavProblem.NOT_RIFF, WavFixtures.wav(magic = "RIFX"))
        reject(WavProblem.NOT_RIFF, "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000hello hello".toByteArray())
        reject(WavProblem.NOT_RIFF, "<html>not audio</html>".toByteArray())
        reject(WavProblem.NOT_WAVE, WavFixtures.wav(wave = "AVI "))
    }

    @Test fun unsupportedFormatsAreRejected() {
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(bits = 8)))
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(bits = 24)))
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(channels = 2)))
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(format = 3, bits = 32))) // 浮動小数
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(format = 0x55))) // MP3
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(format = 2))) // ADPCM
        reject(WavProblem.UNSUPPORTED_FORMAT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(format = 0xFFFE))) // EXTENSIBLE
    }

    @Test fun inconsistentOrBrokenFmtIsRejected() {
        reject(WavProblem.BAD_FMT, WavFixtures.wav(fmtChunk = WavFixtures.fmt(blockAlign = 4)))
        // fmt が短い
        val shortFmt = WavFixtures.chunk("fmt ", 8)
        reject(WavProblem.BAD_FMT, WavFixtures.wav(fmtChunk = shortFmt))
        // fmt が無い(data が先)
        reject(WavProblem.BAD_FMT, WavFixtures.wav(fmtChunk = WavFixtures.chunk("junk", 4)))
        // fmt が 2 つ
        reject(WavProblem.BAD_FMT, WavFixtures.wav(before = WavFixtures.fmt()))
    }

    @Test fun byteRateInTheHeaderIsNotTrusted() {
        // 嘘の byte_rate でも、見る項目(PCM / 16bit / mono / block_align / レート)が正しければ通す
        val wav = WavFixtures.wav(fmtChunk = WavFixtures.fmt(byteRate = 1))
        assertEquals(16_000, WavValidator.validate(wav).sampleRate)
    }

    @Test fun fmtWithExtensionBytesIsAccepted() {
        assertEquals(16_000, WavValidator.validate(WavFixtures.wav(fmtChunk = WavFixtures.fmt(extraBytes = 2))).sampleRate)
    }

    @Test fun extensionChunksBeforeDataAreSkipped() {
        val list = WavFixtures.chunk("LIST", 26)
        val fact = WavFixtures.chunk("fact", 4)
        val odd = WavFixtures.chunk("junk", 5) // 奇数サイズは 1 バイトのパディング
        val wav = WavFixtures.wav(before = list + fact + odd, dataBytes = 3_200)
        val info = WavValidator.validate(wav)
        assertEquals(3_200, info.dataBytes)
        assertEquals(wav.size - 3_200, info.dataOffset)
        assertArrayEquals(wav, WavValidator.read(ByteArrayInputStream(wav)))
    }

    @Test fun hugeExtensionChunkBeforeDataIsRejected() {
        val big = WavFixtures.chunk("LIST", 70_000)
        reject(WavProblem.HEADER_TOO_LARGE, WavFixtures.wav(before = big))
        // 4 GB 級の宣言のチャンクも、読み飛ばさずに断る
        val liar = ByteArray(8).also { "LIST".toByteArray().copyInto(it); for (i in 4..7) it[i] = 0xFF.toByte() }
        reject(WavProblem.HEADER_TOO_LARGE, WavFixtures.wav(before = liar))
    }

    @Test fun dataSizeDeclarationMustMatchTheActualBytes() {
        // 宣言が実データより大きい(切れた WAV)
        reject(WavProblem.SIZE_MISMATCH, WavFixtures.wav(dataBytes = 3_200, declaredData = 4_000))
        // 宣言が小さい(余りがある)
        reject(WavProblem.SIZE_MISMATCH, WavFixtures.wav(dataBytes = 3_200, declaredData = 3_000))
        // 末尾に何かが付いている
        reject(WavProblem.SIZE_MISMATCH, WavFixtures.wav(dataBytes = 3_200, trailing = WavFixtures.chunk("LIST", 10)))
        reject(WavProblem.SIZE_MISMATCH, WavFixtures.wav(dataBytes = 3_200, trailing = byteArrayOf(0)))
        // 奇数バイト(16bit の途中で終わる)
        reject(WavProblem.SIZE_MISMATCH, WavFixtures.wav(dataBytes = 3_201))
    }

    @Test fun outerRiffSizeIsNotChecked() {
        assertEquals(3_200, WavValidator.validate(WavFixtures.wav(riffSize = 0)).dataBytes)
        assertEquals(3_200, WavValidator.validate(WavFixtures.wav(riffSize = 0xFFFFFFFFL)).dataBytes)
    }

    @Test fun emptyAndTinyInputsAreRejected() {
        reject(WavProblem.EMPTY, ByteArray(0))
        reject(WavProblem.TRUNCATED, "RIFF".toByteArray())
        reject(WavProblem.TRUNCATED, "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray())
        reject(WavProblem.TRUNCATED, WavFixtures.wav().copyOf(30)) // fmt の途中
        reject(WavProblem.TRUNCATED, WavFixtures.wav().copyOf(40)) // data の見出しの途中
        reject(WavProblem.NO_DATA, WavFixtures.wav(dataBytes = 0))
    }

    @Test fun dataChunkTruncatedIsRejected() {
        val wav = WavFixtures.wav(dataBytes = 3_200)
        reject(WavProblem.SIZE_MISMATCH, wav.copyOf(wav.size - 2))
        reject(WavProblem.SIZE_MISMATCH, wav.copyOf(44)) // データが 1 バイトも無い(宣言だけ)
    }

    @Test fun overTenMegabytesIsRejectedByDeclarationBeforeReadingTheBody() {
        // 宣言だけが大きい: ヘッダを読んだ時点で拒否し、本文は読まない
        val head = WavFixtures.wav(dataBytes = 0, declaredData = 11L * 1024 * 1024)
        val counting = CountingStream(head, endless = true)
        try {
            WavValidator.read(counting)
            fail()
        } catch (e: WavFormatException) {
            assertEquals(WavProblem.TOO_LARGE, e.reason)
        }
        assertTrue("ヘッダを読んだところで止まる(${counting.consumed} バイト)", counting.consumed < 128 * 1024)
    }

    @Test fun streamingWavWithMaxDataSizeIsRejected() {
        // 0xFFFFFFFF は「サイズ不明のストリーム」の慣習。符号なしで見て上限超過にする
        val head = WavFixtures.wav(dataBytes = 0, declaredData = 0xFFFFFFFFL)
        val counting = CountingStream(head, endless = true)
        try {
            WavValidator.read(counting)
            fail()
        } catch (e: WavFormatException) {
            assertEquals(WavProblem.TOO_LARGE, e.reason)
        }
        assertTrue(counting.consumed < 128 * 1024)
    }

    @Test fun exactlyTenMegabytesIsAcceptedAndOneByteMoreIsNot() {
        // 48 kHz なら 10 MB = 109 秒 < 120 秒なので、バイト数の上限だけが効く
        val max = WavValidator.MAX_BYTES - 44
        val ok = WavFixtures.wav(sampleRate = 48_000, dataBytes = max)
        assertEquals(WavValidator.MAX_BYTES, ok.size)
        assertEquals(max, WavValidator.validate(ok).dataBytes)
        reject(WavProblem.TOO_LARGE, WavFixtures.wav(sampleRate = 48_000, dataBytes = max + 2))
    }

    @Test fun bodyLargerThanDeclaredStopsReadingAtTheCap() {
        // 宣言は小さいが、本文が際限なく続く: 宣言の末尾 +1 バイトで打ち切る(10 MB まで読まない)
        val head = WavFixtures.wav(dataBytes = 3_200)
        val counting = CountingStream(head, endless = true)
        try {
            WavValidator.read(counting)
            fail()
        } catch (e: WavFormatException) {
            assertEquals(WavProblem.SIZE_MISMATCH, e.reason)
        }
        assertTrue("余りを見つけた時点で止まる(${counting.consumed} バイト)", counting.consumed <= head.size + 32 * 1024)
    }

    @Test fun overOneHundredTwentySecondsIsRejectedByDeclaration() {
        // 16 kHz で 130 秒 = 4.16 MB(バイト数の上限の内側で、長さだけが超える)
        val bytes = 130 * 16_000 * 2
        val head = WavFixtures.wav(dataBytes = 0, declaredData = bytes.toLong())
        val counting = CountingStream(head, endless = true)
        try {
            WavValidator.read(counting)
            fail()
        } catch (e: WavFormatException) {
            assertEquals(WavProblem.TOO_LONG, e.reason)
        }
        assertTrue(counting.consumed < 128 * 1024)
        reject(WavProblem.TOO_LONG, WavFixtures.wav(dataBytes = 121 * 16_000 * 2))
    }

    @Test fun exactlyOneHundredTwentySecondsIsAccepted() {
        val wav = WavFixtures.wav(sampleRate = 16_000, dataBytes = 120 * 16_000 * 2)
        assertEquals(120_000, WavValidator.validate(wav).durationMs)
    }

    @Test fun durationLimitIsComparedBeforeRoundingToMilliseconds() {
        // 16 kHz: 120 秒ちょうど = 3,840,000 バイト。1 サンプル(2 バイト)増えると 120.00006 秒で、ミリ秒に丸めると 120000 になってしまう
        for (rate in listOf(16_000, 24_000, 32_000, 40_000)) {
            val limit = 120 * rate * 2
            val ok = WavFixtures.wav(sampleRate = rate, dataBytes = limit)
            assertEquals("$rate Hz の 120 秒ちょうどは受理", 120_000L, WavValidator.validate(ok).durationMs)
            // 境界の 1 サンプル先は、本文を読まずに宣言だけで拒否する
            reject(WavProblem.TOO_LONG, WavFixtures.wav(sampleRate = rate, dataBytes = 0, declaredData = (limit + 2).toLong()))
            reject(WavProblem.TOO_LONG, WavFixtures.wav(sampleRate = rate, dataBytes = limit + 2))
            // 1 サンプル手前も受理(ミリ秒は切り捨て)
            val under = WavFixtures.wav(sampleRate = rate, dataBytes = limit - 2)
            assertEquals(119_999L, WavValidator.validate(under).durationMs)
        }
        assertEquals(3_840_000, 120 * 16_000 * 2)
    }

    @Test fun at48kHzTheByteLimitAlwaysComesBeforeTheDurationLimit() {
        // 48 kHz の 120 秒 = 11.52 MB > 10 MB なので、長さの上限には届く前にバイト数の上限で止まる(どちらでも拒否)
        reject(WavProblem.TOO_LARGE, WavFixtures.wav(sampleRate = 48_000, dataBytes = 0, declaredData = (120L * 48_000 * 2 + 2)))
        reject(WavProblem.TOO_LARGE, WavFixtures.wav(sampleRate = 48_000, dataBytes = 0, declaredData = 120L * 48_000 * 2))
        // 10 MB の内側(109 秒)は受理
        val max = WavValidator.MAX_BYTES - 44
        assertTrue(WavValidator.validate(WavFixtures.wav(sampleRate = 48_000, dataBytes = max)).durationMs < 120_000)
    }

    @Test fun readerReturningInTinyPiecesGivesTheSameResult() {
        val wav = WavFixtures.wav(before = WavFixtures.chunk("LIST", 30), dataBytes = 6_400)
        val slow = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < wav.size) wav[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= wav.size) return -1
                val n = minOf(len, 3, wav.size - i)
                System.arraycopy(wav, i, b, off, n)
                i += n
                return n
            }
        }
        assertArrayEquals(wav, WavValidator.read(slow))
    }

    @Test fun exceptionMessageNeverContainsTheInput() {
        val e = try {
            WavValidator.validate("RIFF-secret-transcript-text-here".toByteArray())
            null
        } catch (e: WavFormatException) {
            e
        }!!
        assertTrue(!e.message!!.contains("secret"))
    }

    /** `head` のあとは、`endless` なら 0x22 を際限なく返す入力。読んだバイト数を数える。 */
    private class CountingStream(private val head: ByteArray, private val endless: Boolean) : InputStream() {
        var consumed = 0L
            private set
        private var i = 0

        override fun read(): Int {
            val b = if (i < head.size) head[i++].toInt() and 0xFF else if (endless) 0x22 else -1
            if (b >= 0) consumed++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                val v = read()
                if (v < 0) break
                b[off + n++] = v.toByte()
                if (n >= 4096) break
            }
            return if (n == 0 && len > 0) -1 else n
        }
    }
}
