package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 挨拶の選び方(段 G。設計書(段階 2)§5.8.1・§5.8.2)と、戻りのチャイム(§5.8.3) */
class StartCueTest {
    private val jst = 9 * 3_600_000L

    /** 2026-11-17(JST)の hh:mm のエポックのミリ秒 */
    private fun at(h: Int, m: Int, dayOffset: Int = 0): Long {
        val midnightJst = 1_794_841_200_000L // 2026-11-17T00:00+09:00
        return midnightJst + dayOffset * 86_400_000L + (h * 60 + m) * 60_000L
    }

    @Test fun theMidnightConstantIsReallyJstMidnight() {
        assertEquals(0, ClockMath.minuteOfDay(at(0, 0), jst))
    }

    @Test fun daypartBoundaries() {
        assertEquals(Daypart.EVENING, StartCue.daypart(at(3, 59), jst))
        assertEquals(Daypart.MORNING, StartCue.daypart(at(4, 0), jst))
        assertEquals(Daypart.MORNING, StartCue.daypart(at(10, 59), jst))
        assertEquals(Daypart.DAY, StartCue.daypart(at(11, 0), jst))
        assertEquals(Daypart.DAY, StartCue.daypart(at(17, 59), jst))
        assertEquals(Daypart.EVENING, StartCue.daypart(at(18, 0), jst))
        assertEquals(Daypart.EVENING, StartCue.daypart(at(0, 0), jst))
    }

    @Test fun firstInTheDaypartGetsTheDaypartGreetingAndTheRestTheShortOne() {
        val first = StartCue.choose(CueScene.SWIPE, at(8, 0), jst, null)
        assertEquals("g_morning_swipe", first.name)
        val key = first.daypartKey!!
        assertEquals("g_plain_swipe", StartCue.choose(CueScene.SWIPE, at(9, 0), jst, key).name)
        assertNull(StartCue.choose(CueScene.SWIPE, at(9, 0), jst, key).daypartKey)
        // スワイプと通知で共通に数える
        assertEquals("g_plain_notice", StartCue.choose(CueScene.NOTIFICATION, at(10, 59), jst, key).name)
        // 時間帯が変わったら、また時間帯の挨拶
        val day = StartCue.choose(CueScene.NOTIFICATION, at(11, 0), jst, key)
        assertEquals("g_day_notice", day.name)
        assertEquals("g_evening_swipe", StartCue.choose(CueScene.SWIPE, at(18, 0), jst, day.daypartKey).name)
        // 次の日の朝は、前の日の朝の印では短くならない
        assertEquals("g_morning_swipe", StartCue.choose(CueScene.SWIPE, at(8, 0, dayOffset = 1), jst, key).name)
    }

    @Test fun theEveningSpansMidnightAsOneDaypart() {
        val evening = StartCue.choose(CueScene.SWIPE, at(23, 0), jst, null)
        assertEquals("g_evening_swipe", evening.name)
        val afterMidnight = StartCue.choose(CueScene.SWIPE, at(1, 0, dayOffset = 1), jst, evening.daypartKey)
        assertEquals("23 時に挨拶したら 1 時は短い形", "g_plain_swipe", afterMidnight.name)
        assertEquals(StartCue.daypartKey(at(23, 0), jst), StartCue.daypartKey(at(3, 59, dayOffset = 1), jst))
        assertNotEquals(StartCue.daypartKey(at(3, 59, dayOffset = 1), jst), StartCue.daypartKey(at(4, 0, dayOffset = 1), jst))
        // 0:30 が最初なら「こんばんは」
        assertEquals("g_evening_notice", StartCue.choose(CueScene.NOTIFICATION, at(0, 30), jst, null).name)
    }

    @Test fun retryIsAlwaysTheShortVoiceAndDoesNotConsumeTheDaypart() {
        val c = StartCue.choose(CueScene.RETRY, at(8, 0), jst, null)
        assertEquals("g_retry", c.name)
        assertNull(c.daypartKey)
    }

    @Test fun theNineNamesMatchTheHostContract() {
        assertEquals(
            setOf(
                "g_morning_swipe", "g_day_swipe", "g_evening_swipe", "g_plain_swipe",
                "g_morning_notice", "g_day_notice", "g_evening_notice", "g_plain_notice", "g_retry",
            ),
            StartCue.NAMES.toSet(),
        )
        assertEquals(9, StartCue.NAMES.size)
        for (scene in CueScene.entries) for (h in 0..23) {
            assertTrue(StartCue.choose(scene, at(h, 0), jst, null).name in StartCue.NAMES)
            assertTrue(StartCue.choose(scene, at(h, 0), jst, StartCue.daypartKey(at(h, 0), jst)).name in StartCue.NAMES)
        }
    }

    @Test fun theChimeIsAValidShortWav() {
        val wav = StartChime.wav()
        val info = WavValidator.validate(wav)
        assertEquals(StartChime.SAMPLE_RATE, info.sampleRate)
        assertEquals(StartChime.DURATION_MS.toLong(), info.durationMs)
        assertTrue(info.durationMs in 300..500)
        // 無音ではない(2 音ある)。最初と最後は 0 付近(クリックを出さない)
        var peak = 0
        for (i in 0 until info.dataBytes / 2) {
            val s = ((wav[44 + i * 2 + 1].toInt() shl 8) or (wav[44 + i * 2].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(s))
        }
        assertTrue("peak=$peak", peak > 8_000)
        assertEquals(0, wav[44].toInt())
        // 返すたびに新しい配列(呼ぶ側が書き換えても次に影響しない)
        assertArrayEquals(StartChime.wav(), StartChime.wav())
        wav.fill(0)
        assertTrue(StartChime.wav().drop(44).any { it != 0.toByte() })
    }
}
