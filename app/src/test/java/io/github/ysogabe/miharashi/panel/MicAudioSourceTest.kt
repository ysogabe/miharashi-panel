package io.github.ysogabe.miharashi.panel

import android.Manifest
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `AudioRecord` の代わり。読み取りは台本([script])どおりに戻り、台本が尽きたら少し待って 0 を返す。 */
private class FakeRecorder : Recorder {
    var init = true
    var recordingFlag = false
    var startThrows: Exception? = null
    var stopThrows: Exception? = null
    var releaseThrows: Exception? = null
    val stops = AtomicInteger()
    val releases = AtomicInteger()
    val script = LinkedBlockingQueue<Int>()
    @Volatile var released = false

    override val initialized get() = init
    override val isRecording get() = recordingFlag && !released

    override fun startRecording() {
        startThrows?.let { throw it }
        recordingFlag = true
    }

    override fun read(buffer: ByteArray, length: Int): Int {
        val n = script.poll(20, TimeUnit.MILLISECONDS) ?: return if (released) -1 else 0
        if (n > 0) for (i in 0 until minOf(n, length)) buffer[i] = (i % 100 + 1).toByte()
        return n
    }

    override fun stop() {
        stops.incrementAndGet()
        recordingFlag = false
        stopThrows?.let { throw it }
    }

    override fun release() {
        releases.incrementAndGet()
        released = true
        releaseThrows?.let { throw it }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MicAudioSourceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class Events {
        val pcm = LinkedBlockingQueue<ByteArray>()
        val errors = LinkedBlockingQueue<VoiceErrorCode>()
        val errorCount = AtomicInteger()
    }

    private fun source(
        recorder: FakeRecorder? = FakeRecorder(), permission: Boolean = true,
        nowNs: () -> Long = System::nanoTime, factoryCalls: AtomicInteger = AtomicInteger(),
    ) = MicAudioSource(
        hasPermission = { permission },
        recorderFactory = RecorderFactory { factoryCalls.incrementAndGet(); recorder },
        nowNs = nowNs, sleepMs = { },
    )

    private fun MicAudioSource.begin(e: Events) = start({ e.pcm.add(it.copyOf()) }, { e.errorCount.incrementAndGet(); e.errors.add(it) })

    // ---- start の契約

    @Test fun noPermissionReportsMicPermissionAndNeverOpensTheMic() {
        val calls = AtomicInteger()
        val rec = FakeRecorder()
        val s = source(rec, permission = false, factoryCalls = calls)
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_PERMISSION, e.errors.poll())
        assertEquals("録音デバイスを作ってもいない", 0, calls.get())
        assertFalse(s.recording)
        assertFalse(rec.recordingFlag)
    }

    @Test fun factoryReturningNullReportsMicUnavailable() {
        val s = source(recorder = null)
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll())
        assertFalse(s.recording)
    }

    @Test fun factoryThrowingReportsMicUnavailableAndSecurityExceptionReportsPermission() {
        val e = Events()
        MicAudioSource({ true }, RecorderFactory { throw IllegalArgumentException("x") }, sleepMs = { }).begin(e)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll())
        MicAudioSource({ true }, RecorderFactory { throw SecurityException("x") }, sleepMs = { }).begin(e)
        assertEquals(VoiceErrorCode.MIC_PERMISSION, e.errors.poll())
    }

    @Test fun startRecordingThatDoesNotReachRecordingStateReleasesAndReportsUnavailable() {
        val rec = FakeRecorder()
        // startRecording は例外なく戻るが、録音状態にならない(別のアプリが占有しているなど)
        val noisy = object : Recorder by rec {
            override val isRecording get() = false
            override fun startRecording() = rec.startRecording()
        }
        val e = Events()
        val s = MicAudioSource({ true }, RecorderFactory { noisy }, sleepMs = { })
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll())
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun startRecordingThrowingReleasesAndReportsUnavailable() {
        val rec = FakeRecorder().apply { startThrows = IllegalStateException("busy") }
        val s = source(rec)
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll())
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun startRecordingSecurityExceptionReportsPermission() {
        val rec = FakeRecorder().apply { startThrows = SecurityException("denied") }
        val s = source(rec)
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_PERMISSION, e.errors.poll())
        assertEquals(1, rec.releases.get())
    }

    @Test fun errorIsReportedSynchronouslyBeforeStartReturns() {
        val s = source(recorder = null)
        var reported = false
        s.start({ }, { reported = true })
        assertTrue("start が戻った時点で onError 済み", reported)
    }

    // ---- 読み取り

    @Test fun pcmChunksAreDeliveredAndRecordingReflectsTheRecorder() {
        val rec = FakeRecorder()
        val s = source(rec)
        val e = Events()
        s.begin(e)
        assertTrue(s.recording)
        rec.script.addAll(listOf(3200, 3200, 1600))
        val a = e.pcm.poll(5, TimeUnit.SECONDS)!!
        val b = e.pcm.poll(5, TimeUnit.SECONDS)!!
        val c = e.pcm.poll(5, TimeUnit.SECONDS)!!
        assertEquals(3200, a.size)
        assertEquals(3200, b.size)
        assertEquals("短い読み取りは、読めたバイト数ぶんだけ渡す", 1600, c.size)
        assertEquals(1, a[0].toInt())
        s.stop()
        assertFalse(s.recording)
        assertEquals(0, e.errorCount.get())
    }

    @Test fun recordingIsTheActualRecorderStateNotTheSessionState() {
        val rec = FakeRecorder()
        val s = source(rec)
        s.begin(Events())
        assertTrue(s.recording)
        rec.recordingFlag = false // OS 側が録音を止めた(別のアプリに奪われた など)
        assertFalse("AudioRecord の実際の状態に連動する", s.recording)
        s.stop()
    }

    @Test fun negativeReadStopsReleasesAndReportsUnavailableOnce() {
        val rec = FakeRecorder()
        val s = source(rec)
        val e = Events()
        s.begin(e)
        rec.script.add(-3) // ERROR_INVALID_OPERATION
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll(5, TimeUnit.SECONDS))
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
        s.stop() // 冪等
        assertEquals(1, e.errorCount.get())
        assertEquals(1, rec.releases.get())
    }

    @Test fun zeroByteReadsThatContinueReportUnavailable() {
        val rec = FakeRecorder()
        var t = 0L
        val s = source(rec, nowNs = { t += 400_000_000L; t }) // 呼ぶたび 400 ms 進む
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_UNAVAILABLE, e.errors.poll(5, TimeUnit.SECONDS))
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun aShortRunOfZeroReadsIsTolerated() {
        val rec = FakeRecorder()
        var t = 0L
        val s = source(rec, nowNs = { t += 1_000_000L; t }) // 1 ms ずつ
        val e = Events()
        s.begin(e)
        rec.script.addAll(listOf(0, 0, 3200))
        assertNotNull(e.pcm.poll(5, TimeUnit.SECONDS))
        assertEquals(0, e.errorCount.get())
        s.stop()
    }

    @Test fun stopNeverReportsAnErrorFromTheInterruptedRead() {
        val rec = FakeRecorder()
        val s = source(rec)
        val e = Events()
        s.begin(e)
        s.stop()
        // stop 後に read が負で戻っても(release 済みのデバイス)、取消のたびに偽のエラーを出さない
        rec.script.add(-1)
        Thread.sleep(150)
        assertEquals(0, e.errorCount.get())
        assertEquals(0, e.pcm.size)
    }

    // ---- stop の契約

    @Test fun stopIsIdempotentAndReleasesOnce() {
        val rec = FakeRecorder()
        val s = source(rec)
        s.begin(Events())
        s.stop()
        s.stop()
        s.stop()
        assertEquals(1, rec.stops.get())
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun stopBeforeStartIsHarmless() {
        val s = source()
        s.stop()
        assertFalse(s.recording)
    }

    @Test fun stopThrowingStillReleases() {
        val rec = FakeRecorder().apply { stopThrows = IllegalStateException("stop failed") }
        val s = source(rec)
        s.begin(Events())
        s.stop() // 例外は出ない
        assertEquals("AudioRecord.stop が例外でも、finally で release する", 1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun releaseThrowingDoesNotEscapeAndRecordingBecomesFalse() {
        val rec = FakeRecorder().apply { stopThrows = IllegalStateException("a"); releaseThrows = IllegalStateException("b") }
        val s = source(rec)
        s.begin(Events())
        s.stop()
        assertEquals(1, rec.releases.get())
        assertFalse(s.recording)
    }

    @Test fun startingAgainStopsAndReleasesThePreviousRecorder() {
        val first = FakeRecorder()
        val second = FakeRecorder()
        val queue = ArrayDeque(listOf(first, second))
        val s = MicAudioSource({ true }, RecorderFactory { queue.removeFirst() }, sleepMs = { })
        s.begin(Events())
        s.begin(Events())
        assertEquals(1, first.releases.get())
        assertEquals(0, second.releases.get())
        assertTrue(s.recording)
        s.stop()
        assertEquals(1, second.releases.get())
    }

    @Test fun readerThreadEndsAfterStop() {
        val rec = FakeRecorder()
        val threads = mutableListOf<Thread>()
        val s = MicAudioSource({ true }, RecorderFactory { rec }, sleepMs = { },
            threadFactory = { r -> Thread(r, "test-mic").apply { isDaemon = true }.also { threads += it } })
        s.begin(Events())
        s.stop()
        threads.single().join(3000)
        assertFalse("読み取りスレッドが止まる", threads.single().isAlive)
    }

    // ---- 実際の Android クラス(Robolectric のシャドウ)の経路

    private fun filesUnderAppDirs(): Set<String> =
        listOf(context.filesDir, context.cacheDir, context.codeCacheDir, context.getExternalFilesDir(null))
            .filterNotNull().flatMap { dir -> dir.walkTopDown().filter(File::isFile).map(File::getAbsolutePath).toList() }.toSet()

    @Test fun realAudioRecordPathDeliversPcmAndWritesNoFiles() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val before = filesUnderAppDirs()
        val s = MicAudioSource(context)
        val e = Events()
        s.begin(e)
        assertEquals(0, e.errorCount.get())
        assertTrue("実際の録音状態", s.recording)
        val got = CountDownLatch(3)
        val first = e.pcm.poll(5, TimeUnit.SECONDS)
        assertNotNull(first)
        assertEquals(MicAudioSource.CHUNK_BYTES, first!!.size)
        got.countDown()
        s.stop()
        assertFalse(s.recording)
        assertEquals("録音データをファイルに書かない", before, filesUnderAppDirs())
    }

    @Test fun realPathWithoutPermissionReportsMicPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val s = MicAudioSource(context)
        val e = Events()
        s.begin(e)
        assertEquals(VoiceErrorCode.MIC_PERMISSION, e.errors.poll())
        assertFalse(s.recording)
    }

    @Test fun chunkSizeIs100msOfPcm16() {
        assertEquals(3200, MicAudioSource.CHUNK_BYTES)
    }
}
