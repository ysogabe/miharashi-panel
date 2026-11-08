package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/** `AudioTrack` の薄い包み(テストで差し替える)。stop / release は何度呼んでも安全であること。 */
internal interface PcmTrack {
    fun play()

    /** ブロッキング書き込み。書けたバイト数、負はエラー */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    /** 再生済みのフレーム数(`playbackHeadPosition`) */
    val playedFrames: Int

    /** 書き込み済みを最後まで鳴らして止める(ストリームの `stop()`) */
    fun stopAfterDrain()

    /** 今すぐ止める(未再生の分は捨てる) */
    fun stopNow()
    fun release()
}

internal fun interface PcmTrackFactory {
    /** 16 bit / mono の出力を作る。作れなければ null */
    fun create(sampleRate: Int, bufferBytes: Int): PcmTrack?
}

internal class AudioTrackOutput(private val track: AudioTrack) : PcmTrack {
    override fun play() = track.play()
    override fun write(data: ByteArray, offset: Int, length: Int): Int = track.write(data, offset, length)
    override val playedFrames: Int get() = track.playbackHeadPosition
    override fun stopAfterDrain() = track.stop()
    override fun stopNow() {
        track.pause()
        track.flush()
        track.stop()
    }

    override fun release() = track.release()

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun factory() = PcmTrackFactory { sampleRate, bufferBytes ->
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return@PcmTrackFactory null
            val track = AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, bufferBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                try { track.release() } catch (_: Exception) { }
                return@PcmTrackFactory null
            }
            AudioTrackOutput(track)
        }
    }
}

/**
 * 返答の WAV の再生(設計書 §4.1, §6)。`VoiceSession` が渡す WAV(メモリ上の `ByteArray`)を [WavValidator.validate] で確かめ、
 * ヘッダのあとの PCM を `AudioTrack`(`MODE_STREAM`、`USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`)へ専用スレッドで書く。
 * OS のメディアパーサには渡さず、**ファイルにも書かない**。
 *
 * - [play] はノンブロッキング。再生の前に `AUDIOFOCUS_GAIN_TRANSIENT` を取り、終わり・停止・失敗のどれでも手放す
 *   (取れなくても再生は続ける: 内蔵スピーカー専用のキオスクで、返答は利用者への主な出力のため。他アプリに奪われたら失敗にする)。
 * - `onDone(true)` は最後まで再生、`onDone(false)` は検証・出力の作成・書き込みの失敗かフォーカスの喪失。**どのスレッドから呼ばれることもある**。
 *   セッションごとに**ちょうど 1 回**で、[stop] したあとは呼ばない。
 * - [stop] は冪等・ノンブロッキングで、再生中でも即座に止めて release する。マイクは開かない(半二重は `VoiceSession` が守る)。
 */
class WavPlayer internal constructor(
    private val audioManager: AudioManager,
    private val trackFactory: PcmTrackFactory,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val threadFactory: (Runnable) -> Thread = { r -> Thread(r, "daidai-wav").apply { isDaemon = true } },
    /** フォーカスの取得の呼び出し(取得の最中に [stop] が走る競合の試験で、ここで止める) */
    private val requestFocusCall: (AudioFocusRequest) -> Int = { audioManager.requestAudioFocus(it) },
) : ReplyPlayer {
    constructor(context: Context) : this(
        context.getSystemService(AudioManager::class.java),
        AudioTrackOutput.factory(),
    )

    /**
     * 1 回の再生。資源([track]・[focus])の公開と取消は、この Run の monitor で直列にする:
     * 作成中([requestFocus] や `trackFactory.create`)に [stop] が走っても、作成側が公開しようとした時点で取消済み
     * ([cancelled] か [cleaned])だと分かり、作ったその場で解放する。公開が先なら [cleanup] が解放する。どちらでも漏れない。
     */
    private class Run(val onDone: (Boolean) -> Unit) {
        val done = AtomicBoolean(false)

        /** [cleanup] 済み(Run の monitor の中でだけ読み書きする) */
        var cleaned = false

        @Volatile
        var track: PcmTrack? = null

        @Volatile
        var focus: AudioFocusRequest? = null

        /** 止められた(以後 `onDone` は呼ばない)。書くのは Run の monitor の中だけ */
        @Volatile
        var cancelled = false
    }

    private val lock = Any()
    private var current: Run? = null

    override fun play(wav: ByteArray, onDone: (Boolean) -> Unit) {
        stop() // 前の再生が残っていれば止める
        val info = try {
            WavValidator.validate(wav)
        } catch (_: Exception) { // WavFormatException ほか。入力の内容は持ち出さない
            onDone(false)
            return
        }
        val run = Run(onDone)
        synchronized(lock) { current = run }
        try {
            threadFactory(Runnable { runPlayback(run, wav, info) }).start()
        } catch (_: Exception) {
            cleanup(run)
            finish(run, false)
        }
    }

    override fun stop() {
        val run = synchronized(lock) { current.also { current = null } } ?: return
        synchronized(run) { run.cancelled = true }
        run.done.set(true) // 以後 onDone は呼ばない
        cleanup(run)
    }

    private fun runPlayback(run: Run, wav: ByteArray, info: WavInfo) {
        var success = false
        try {
            val focus = requestFocus(run)
            if (focus != null && !publishFocus(run, focus)) { // 取得の間に止められた: 取れたその場で手放す
                releaseFocus(focus)
                return
            }
            if (run.cancelled) return
            val track = trackFactory.create(info.sampleRate, BUFFER_BYTES) ?: return
            if (!publishTrack(run, track)) { // 作成の間に止められた: 作ったその場で止めて release
                releaseTrack(track)
                return
            }
            if (run.cancelled) return
            track.play()
            val startedAtMs = nowMs()
            var offset = info.dataOffset
            val end = info.dataOffset + info.dataBytes
            var zeroWrites = 0
            while (offset < end && !run.cancelled) {
                val n = track.write(wav, offset, minOf(WRITE_STEP, end - offset))
                if (run.cancelled) return
                if (n < 0) return
                if (n == 0) {
                    if (++zeroWrites > MAX_ZERO_WRITES) return
                    sleepMs(POLL_MS)
                    continue
                }
                zeroWrites = 0
                offset += n
            }
            if (run.cancelled) return
            // 書いたぶんが鳴り終わるまで、**再生中のまま**待つ(実機の AudioTrack は stop 後の再生位置を 0 に戻すので、stop より前に見る)。
            // 位置が進まない機種でも、再生の開始から再生時間 + 余裕で打ち切る(書き終えているので成功)
            val totalFrames = info.dataBytes / 2
            val limitMs = info.durationMs + DRAIN_MARGIN_MS
            while (!run.cancelled && track.playedFrames < totalFrames && nowMs() - startedAtMs < limitMs) {
                sleepMs(POLL_MS)
            }
            if (run.cancelled) return
            track.stopAfterDrain()
            success = !run.cancelled
        } catch (_: Exception) {
            success = false
        } finally {
            cleanup(run)
            synchronized(lock) { if (current === run) current = null }
            finish(run, success)
        }
    }

    private fun requestFocus(run: Run): AudioFocusRequest? {
        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioTrackOutput.ATTRIBUTES)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        // 他に奪われた: 止めて失敗として知らせる(テキストは画面に残る)
                        synchronized(run) { run.cancelled = true }
                        cleanup(run)
                        finish(run, false)
                    }
                }
                .build()
            requestFocusCall(request)
            request // 取れなくても(FAILED でも)鳴らす。手放しだけは必ず行う
        } catch (_: Exception) {
            null
        }
    }

    /** フォーカスを Run に公開する。止められていた([cancelled] か [cleaned])なら公開せず false(呼び出し側がその場で手放す) */
    private fun publishFocus(run: Run, focus: AudioFocusRequest): Boolean = synchronized(run) {
        if (run.cancelled || run.cleaned) {
            false
        } else {
            run.focus = focus
            true
        }
    }

    /** 出力を Run に公開する。止められていたら公開せず false(呼び出し側がその場で release する) */
    private fun publishTrack(run: Run, track: PcmTrack): Boolean = synchronized(run) {
        if (run.cancelled || run.cleaned) {
            false
        } else {
            run.track = track
            true
        }
    }

    /** 出力の停止・release とフォーカスの解放。一度だけ。どのスレッドから呼んでもよい */
    private fun cleanup(run: Run) {
        val track: PcmTrack?
        val focus: AudioFocusRequest?
        synchronized(run) { // 公開済みの資源の取り出しと cleaned の設定を一緒に行う。これ以後の公開は拒まれる
            if (run.cleaned) return
            run.cleaned = true
            track = run.track
            focus = run.focus
        }
        if (track != null) releaseTrack(track)
        if (focus != null) releaseFocus(focus)
    }

    private fun releaseTrack(track: PcmTrack) {
        try {
            track.stopNow()
        } catch (_: Exception) {
        } finally {
            try { track.release() } catch (_: Exception) { }
        }
    }

    private fun releaseFocus(focus: AudioFocusRequest) {
        try { audioManager.abandonAudioFocusRequest(focus) } catch (_: Exception) { }
    }

    private fun finish(run: Run, success: Boolean) {
        if (!run.done.compareAndSet(false, true)) return
        try {
            run.onDone(success)
        } catch (_: Exception) {
            // 受け側の失敗で再生側を壊さない
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val WRITE_STEP = 4 * 1024
        const val POLL_MS = 20L
        const val MAX_ZERO_WRITES = 100
        const val DRAIN_MARGIN_MS = 1_500L
    }
}
