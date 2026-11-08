package io.github.ysogabe.miharashi.panel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * `AudioRecord` の薄い包み(テストで差し替える)。実体は [AudioRecordRecorder]。
 * 実装は **stop と release を別々に呼べて、どちらも何度呼んでも安全** であること。
 */
internal interface Recorder {
    /** 初期化に成功したか(`AudioRecord.STATE_INITIALIZED`) */
    val initialized: Boolean

    /** 実際の録音状態(`recordingState == RECORDSTATE_RECORDING`)。release 後は false */
    val isRecording: Boolean

    fun startRecording()

    /** ブロッキング読み取り。読めたバイト数、0(読めず)、負(エラー) */
    fun read(buffer: ByteArray, length: Int): Int
    fun stop()
    fun release()
}

/** マイクの録音を作る。作れなければ null。 */
internal fun interface RecorderFactory {
    fun create(): Recorder?
}

internal class AudioRecordRecorder(private val record: AudioRecord) : Recorder {
    override val initialized: Boolean get() = record.state == AudioRecord.STATE_INITIALIZED
    override val isRecording: Boolean
        get() = try {
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: RuntimeException) { // release 後など
            false
        }

    override fun startRecording() = record.startRecording()
    override fun read(buffer: ByteArray, length: Int): Int = record.read(buffer, 0, length)
    override fun stop() = record.stop()
    override fun release() = record.release()

    companion object {
        /** 16 kHz / mono / PCM16。`VOICE_RECOGNITION` を先に、作れなければ `MIC` で作る */
        fun create(): Recorder? {
            val minBuffer = AudioRecord.getMinBufferSize(
                VoiceLimits.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return null
            val bufferBytes = maxOf(minBuffer, MicAudioSource.CHUNK_BYTES * 4)
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val record = try {
                    AudioRecord(
                        source, VoiceLimits.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
                    )
                } catch (_: Exception) { // IllegalArgumentException / UnsupportedOperationException など
                    continue
                }
                val recorder = AudioRecordRecorder(record)
                if (recorder.initialized) return recorder
                try { record.release() } catch (_: Exception) { }
            }
            return null
        }
    }
}

/**
 * マイク(設計書 §4.1, §5.4, §6, §7)。`AudioRecord`(16 kHz / mono / PCM16)を専用の読み取りスレッドで約 100 ms(3,200 バイト)ずつ読んで
 * `onPcm` に渡す。**アプリでマイクを開く実体はここだけ**で、呼ぶのは `VoiceSession` だけ(Intent・他アプリからは開けない)。
 *
 * - [start] は**同期**: 権限が無ければ `onError(mic_permission)`、初期化・開始に失敗したら `onError(mic_unavailable)` を呼んで戻る
 *   (録音は始めない)。戻った時点で、録音中([recording] が true)か `onError` 済みのどちらか。
 *   権限ダイアログは出さない(キオスク運用。権限は `scripts/install.sh` が `pm grant` する)。
 * - [stop] は停止と release を兼ねる。冪等・ノンブロッキング(読み取りスレッドを待たない)・通信に依存しない。
 *   `AudioRecord.stop()` が例外を投げても **`finally` で必ず `release()`** する。どのスレッドから呼んでもよい。
 *   停止後は `onPcm` / `onError` を呼ばない。
 * - [recording] は `AudioRecord.recordingState == RECORDSTATE_RECORDING` の**実際の値**(セッションの状態ではない)。画面の「マイク使用中」に連動する。
 * - 録音データはメモリ上のバッファから `onPcm` に渡すだけで、**ファイルに書かない**。バッファは使い回す(受け側がコピーする)。
 * - 読み取り中のエラー(負の戻り値)や、0 バイトの読み取りが [ZERO_READ_LIMIT_MS] 続く場合は、止めて `onError(mic_unavailable)`。
 *
 * ## マイクを止める方法(運用。R16 の文書は T9。設計書 §7)
 * ① 本体のマイクオフスイッチ(一次手段。音が厳密に 0 になるので `mic_silent` で検出して案内する)、
 * ② 設定の `daidai_url` を空にする(音声機能が完全に止まり、この型は作られない)、
 * ③ `adb shell pm revoke io.github.ysogabe.miharashi.panel android.permission.RECORD_AUDIO`。
 */
class MicAudioSource internal constructor(
    private val hasPermission: () -> Boolean,
    private val recorderFactory: RecorderFactory,
    private val nowNs: () -> Long = System::nanoTime,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val threadFactory: (Runnable) -> Thread = { r -> Thread(r, "daidai-mic").apply { isDaemon = true } },
) : AudioSource {
    constructor(context: Context) : this(
        hasPermission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        recorderFactory = RecorderFactory { AudioRecordRecorder.create() },
    )

    private val lock = Any()

    /** 録音中の [Recorder](なければ null)。[lock] の中でだけ書く */
    @Volatile
    private var current: Recorder? = null

    override val recording: Boolean get() = current?.isRecording == true

    override fun start(onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) {
        stop() // 二重の開始は前のものを止めてから
        if (!hasPermission()) {
            onError(VoiceErrorCode.MIC_PERMISSION)
            return
        }
        var failure: VoiceErrorCode? = null
        synchronized(lock) {
            val recorder = try {
                recorderFactory.create()
            } catch (_: SecurityException) {
                failure = VoiceErrorCode.MIC_PERMISSION
                null
            } catch (_: Exception) {
                null
            }
            if (recorder == null) {
                failure = failure ?: VoiceErrorCode.MIC_UNAVAILABLE
                return@synchronized
            }
            var started = false
            try {
                recorder.startRecording()
                started = recorder.isRecording
            } catch (_: SecurityException) {
                failure = VoiceErrorCode.MIC_PERMISSION
            } catch (_: Exception) {
                // IllegalStateException など
            }
            if (!started) {
                releaseQuietly(recorder)
                failure = failure ?: VoiceErrorCode.MIC_UNAVAILABLE
                return@synchronized
            }
            current = recorder
            try {
                threadFactory(Runnable { readLoop(recorder, onPcm, onError) }).start()
            } catch (_: Exception) {
                current = null
                releaseQuietly(recorder)
                failure = VoiceErrorCode.MIC_UNAVAILABLE
                return@synchronized
            }
        }
        failure?.let(onError)
    }

    override fun stop() {
        val recorder = synchronized(lock) { current.also { current = null } } ?: return
        try {
            recorder.stop()
        } catch (_: Exception) {
            // 停止が失敗しても release へ進む
        } finally {
            releaseQuietly(recorder) // 例外でも必ず release
        }
    }

    /** 読み取りスレッド。[current] がこの録音でなくなったら(stop された)、何も呼ばずに終わる */
    private fun readLoop(recorder: Recorder, onPcm: (ByteArray) -> Unit, onError: (VoiceErrorCode) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var zeroSinceNs = -1L
        while (current === recorder) {
            val n = try {
                recorder.read(buffer, CHUNK_BYTES)
            } catch (_: Exception) {
                -1
            }
            if (current !== recorder) return // stop 後に戻ってきた読み取りは捨てる(onError も出さない)
            when {
                n > 0 -> {
                    zeroSinceNs = -1L
                    onPcm(if (n == buffer.size) buffer else buffer.copyOf(n))
                }
                n == 0 -> {
                    val now = nowNs()
                    if (zeroSinceNs < 0) zeroSinceNs = now
                    if ((now - zeroSinceNs) / 1_000_000L >= ZERO_READ_LIMIT_MS) {
                        failReading(recorder, onError)
                        return
                    }
                    try { sleepMs(ZERO_READ_SLEEP_MS) } catch (_: InterruptedException) { return }
                }
                else -> {
                    failReading(recorder, onError)
                    return
                }
            }
        }
    }

    /** 録音中の読み取りの失敗: 止めて release してから知らせる(まだ自分の録音のときだけ) */
    private fun failReading(recorder: Recorder, onError: (VoiceErrorCode) -> Unit) {
        val mine = synchronized(lock) { (current === recorder).also { if (it) current = null } }
        if (!mine) return
        try {
            recorder.stop()
        } catch (_: Exception) {
        } finally {
            releaseQuietly(recorder)
        }
        onError(VoiceErrorCode.MIC_UNAVAILABLE)
    }

    private fun releaseQuietly(recorder: Recorder) {
        try {
            recorder.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 100 ms 分(16 kHz × 2 バイト) */
        const val CHUNK_BYTES = VoiceLimits.BYTES_PER_SECOND / 10

        /** 0 バイトの読み取りがこの時間続いたら使用不可とみなす */
        const val ZERO_READ_LIMIT_MS = 1_000L
        private const val ZERO_READ_SLEEP_MS = 10L
    }
}
