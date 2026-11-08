package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 再生を台本どおりに進める `AudioTrack` の代わり */
private class FakeTrack(private val totalFramesToReport: Int = Int.MAX_VALUE) : PcmTrack {
    val written = AtomicInteger()
    val plays = AtomicInteger()
    val drains = AtomicInteger()
    val stopsNow = AtomicInteger()
    val releases = AtomicInteger()
    @Volatile var writeResult: ((Int) -> Int) = { it }
    @Volatile var stopNowThrows = false
    val gate = CountDownLatch(0)
    @Volatile var blockWrites: CountDownLatch? = null
    @Volatile var played = 0

    override fun play() { plays.incrementAndGet() }
    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        blockWrites?.await(5, TimeUnit.SECONDS)
        val n = writeResult(length)
        if (n > 0) { written.addAndGet(n); played = minOf(totalFramesToReport, played + n / 2) }
        return n
    }
    override val playedFrames get() = played
    override fun stopAfterDrain() { drains.incrementAndGet() }
    override fun stopNow() { stopsNow.incrementAndGet(); if (stopNowThrows) throw IllegalStateException("x") }
    override fun release() { releases.incrementAndGet() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WavPlayerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val audioManager: AudioManager get() = context.getSystemService(AudioManager::class.java)

    private val wav = WavFixtures.wav(sampleRate = 24_000, dataBytes = 4_800)

    private fun player(track: FakeTrack?): WavPlayer =
        WavPlayer(audioManager, PcmTrackFactory { _, _ -> track }, sleepMs = { Thread.sleep(1) })

    private fun WavPlayer.playAndWait(data: ByteArray, seconds: Long = 5): Boolean? {
        val q = LinkedBlockingQueue<Boolean>()
        play(data) { q.add(it) }
        return q.poll(seconds, TimeUnit.SECONDS)
    }

    @Test fun playsToTheEndAndReportsTrue() {
        val track = FakeTrack()
        val p = player(track)
        assertEquals(true, p.playAndWait(wav))
        assertEquals("ヘッダを除いた PCM だけ書く", 4_800, track.written.get())
        assertEquals(1, track.plays.get())
        assertEquals(1, track.drains.get())
        assertEquals("終わったら release", 1, track.releases.get())
    }

    @Test fun brokenBytesReportFalseWithoutCreatingAnOutput() {
        val created = AtomicInteger()
        val p = WavPlayer(audioManager, PcmTrackFactory { _, _ -> created.incrementAndGet(); FakeTrack() })
        assertEquals(false, p.playAndWait(byteArrayOf(1, 2, 3, 4)))
        assertEquals(false, p.playAndWait(ByteArray(0)))
        assertEquals(false, p.playAndWait("RIFF....WAVEjunk".toByteArray()))
        assertEquals(0, created.get())
    }

    @Test fun outputCreationFailureReportsFalse() {
        assertEquals(false, player(null).playAndWait(wav))
    }

    @Test fun writeErrorReportsFalseAndReleases() {
        val track = FakeTrack().apply { writeResult = { -6 } } // ERROR_DEAD_OBJECT
        assertEquals(false, player(track).playAndWait(wav))
        assertEquals(1, track.releases.get())
    }

    @Test fun onDoneIsCalledExactlyOnce() {
        val track = FakeTrack()
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        player(track).play(wav) { calls.incrementAndGet(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, calls.get())
    }

    @Test fun stopIsIdempotentStopsImmediatelyAndNeverCallsOnDone() {
        val track = FakeTrack().apply { blockWrites = CountDownLatch(1) }
        val p = player(track)
        val calls = AtomicInteger()
        p.play(wav) { calls.incrementAndGet() }
        Thread.sleep(50)
        p.stop()
        p.stop()
        p.stop()
        track.blockWrites!!.countDown() // ブロック中の書き込みが戻る
        Thread.sleep(150)
        assertEquals("止めたあとは onDone を呼ばない", 0, calls.get())
        assertEquals("release は一度だけ", 1, track.releases.get())
        assertEquals(1, track.stopsNow.get())
    }

    @Test fun stopBeforePlayAndAfterCompletionIsHarmless() {
        val track = FakeTrack()
        val p = player(track)
        p.stop()
        assertEquals(true, p.playAndWait(wav))
        p.stop()
        assertEquals(1, track.releases.get())
    }

    @Test fun stopReleasesEvenWhenStoppingTheOutputThrows() {
        val track = FakeTrack().apply { blockWrites = CountDownLatch(1); stopNowThrows = true }
        val p = player(track)
        p.play(wav) { }
        Thread.sleep(50)
        p.stop()
        assertEquals(1, track.releases.get())
        track.blockWrites!!.countDown()
    }

    @Test fun playingAgainStopsThePreviousPlayback() {
        val first = FakeTrack().apply { blockWrites = CountDownLatch(1) }
        val second = FakeTrack()
        val tracks = ArrayDeque(listOf(first, second))
        val p = WavPlayer(audioManager, PcmTrackFactory { _, _ -> tracks.removeFirst() }, sleepMs = { Thread.sleep(1) })
        val firstDone = AtomicInteger()
        p.play(wav) { firstDone.incrementAndGet() }
        Thread.sleep(50)
        assertEquals(true, p.playAndWait(wav))
        first.blockWrites!!.countDown()
        Thread.sleep(100)
        assertEquals(1, first.releases.get())
        assertEquals(0, firstDone.get())
    }

    @Test fun playbackThatNeverAdvancesStillEndsAfterTheDurationPlusMargin() {
        // 再生位置が進まない機種でも、書き終えていれば再生時間 + 余裕で成功として終わる(スリープは打ち切りのために無待ちにする)
        val track = FakeTrack(totalFramesToReport = 0)
        val p = WavPlayer(audioManager, PcmTrackFactory { _, _ -> track }, sleepMs = { })
        assertEquals(true, p.playAndWait(wav))
        assertEquals(1, track.releases.get())
    }

    // ---- AudioFocus

    @Test fun takesTransientFocusBeforePlayingAndAbandonsItAtTheEnd() {
        val am = shadowOf(audioManager)
        val track = FakeTrack()
        assertEquals(true, player(track).playAndWait(wav))
        val request = am.lastAudioFocusRequest
        assertNotNull("フォーカスを取った", request)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request!!.audioFocusRequest.focusGain)
        assertNotNull("終わったら手放した", am.lastAbandonedAudioFocusRequest)
    }

    @Test fun abandonsFocusOnStopAndOnFailure() {
        val am = shadowOf(audioManager)
        val track = FakeTrack().apply { blockWrites = CountDownLatch(1) }
        val p = player(track)
        p.play(wav) { }
        Thread.sleep(50)
        assertNotNull(am.lastAudioFocusRequest)
        p.stop()
        assertNotNull("stop でも手放す", am.lastAbandonedAudioFocusRequest)
        track.blockWrites!!.countDown()
    }

    @Test fun losingFocusStopsPlaybackAndReportsFalse() {
        val am = shadowOf(audioManager)
        val track = FakeTrack().apply { blockWrites = CountDownLatch(1) }
        val q = LinkedBlockingQueue<Boolean>()
        val p = player(track)
        p.play(wav) { q.add(it) }
        Thread.sleep(50)
        am.lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(false, q.poll(5, TimeUnit.SECONDS))
        assertEquals(1, track.releases.get())
        track.blockWrites!!.countDown()
    }

    @Test fun refusedFocusStillPlaysAndStillAbandons() {
        val am = shadowOf(audioManager)
        am.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val track = FakeTrack()
        assertEquals(true, player(track).playAndWait(wav))
        assertNotNull(am.lastAbandonedAudioFocusRequest)
    }

    // ---- 実際の AudioTrack(シャドウ)

    @Test fun realAudioTrackPathPlaysTheWav() {
        val p = WavPlayer(context)
        assertEquals(true, p.playAndWait(wav, seconds = 10))
        p.stop()
    }

    @Test fun nothingIsWrittenToFiles() {
        val before = listOf(context.filesDir, context.cacheDir).flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
        val p = WavPlayer(context)
        assertEquals(true, p.playAndWait(wav, seconds = 10))
        val after = listOf(context.filesDir, context.cacheDir).flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
        assertEquals(before, after)
    }
}
