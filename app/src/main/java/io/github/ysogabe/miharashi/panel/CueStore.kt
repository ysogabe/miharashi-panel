package io.github.ysogabe.miharashi.panel

import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 目録の 1 件(使う項目だけ)。`sha256` は小文字の 16 進 64 文字、`ms` は 1〜[VoiceLimits.CUE_MAX_MS] に検証済み */
data class CueEntry(val sha256: String, val ms: Long)

/** `GET /v1/voice/cues` の目録。[cues] は形の正しい項目だけ(名前は `^[a-z][a-z0-9_]{0,31}$`) */
data class CueManifest(val voice: String?, val cues: Map<String, CueEntry>)

/** 挨拶の取得の口(本番は [BridgeClient])。どちらもブロックする。失敗は例外(内容はメッセージに入れない) */
interface CueFetcher {
    fun fetchCueManifest(): CueManifest

    /** `name` は [StartCue.NAMES] のどれか(それ以外は呼ぶ側が渡さない。実装も拒否する)。検証済みの WAV を返す */
    fun fetchCue(name: String): ByteArray
}

/** [DaidaiVoice] が使う挨拶の置き場。どのスレッドから呼んでもよく、ノンブロッキング */
interface CueSource {
    /** 検証済みの挨拶の WAV(無ければ null。呼ぶ側はチャイムに戻す) */
    fun get(name: String): ByteArray?

    /** 取り直しが要るなら(初回・失敗のあと・前の成功から 30 分)裏で取りに行く。待たない */
    fun refreshIfDue()

    /** 片づける。以後は取りに行かない(持っている分は捨てる) */
    fun close()
}

fun interface CueSourceFactory {
    /** 接続先(URL・トークン)ごとに 1 つ。作れなければ null(挨拶はチャイムになる) */
    fun create(settings: DaidaiSettings): CueSource?
}

/**
 * 挨拶の音声の取得とメモリでの保持(段 G。設計書(段階 2)§5.8.3 の W2)。ファイルには書かない(返答の WAV と同じ扱い)。
 *
 * - 取りに行くのは [StartCue.NAMES] の 9 本だけ。目録の鍵を URL に入れない(目録は sha256 と長さの照合にだけ使う)。
 * - 使うのは、目録の `sha256` と一致し、[WavValidator] を通り、**実データの長さ**も目録の `ms` も 5 秒以下のものだけ。
 * - 前回と sha256 が同じものは取り直さない(30 分ごとの取り直しは、ふつう目録 1 回で済む)。
 * - 目録が 404(旧ホスト・挨拶の検査に落ちたホスト)なら持っている分を捨てる(チャイムになる)。通信の失敗では持っている分を残す。
 *   どちらも「失敗」として、次の [refreshIfDue](次の入場の前)で取り直す。
 * - 取得は自分の [runner](スレッド)で、同時に 1 本だけ。セッションの通信とは別の接続なので、セッションの中断(`abortInFlight`)に巻き込まれない。
 */
class CueStore(
    private val fetcher: CueFetcher,
    private val runner: (Runnable) -> Unit,
    private val nowMs: () -> Long,
    private val onClose: () -> Unit = {},
) : CueSource {
    private class Cached(val sha256: String, val wav: ByteArray)

    private val lock = Any()

    @Volatile private var cache: Map<String, Cached> = emptyMap()
    private var running = false
    private var closed = false
    private var lastSuccessMs: Long? = null
    private var failed = false

    override fun get(name: String): ByteArray? = cache[name]?.wav

    override fun refreshIfDue() {
        synchronized(lock) {
            if (closed || running) return
            val last = lastSuccessMs
            if (!failed && last != null && nowMs() - last < VoiceLimits.CUE_REFRESH_MS) return
            running = true
        }
        try {
            runner(Runnable { refresh() })
        } catch (_: RuntimeException) { // RejectedExecutionException: 閉じた
            synchronized(lock) { running = false }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            cache = emptyMap()
        }
        try {
            onClose()
        } catch (_: RuntimeException) {
        }
    }

    private fun refresh() {
        var ok = false
        try {
            ok = refreshOnce()
        } catch (_: Exception) {
            ok = false
        } finally {
            synchronized(lock) {
                running = false
                if (!closed) {
                    failed = !ok
                    if (ok) lastSuccessMs = nowMs()
                }
            }
        }
    }

    /** 1 回の取り直し。すべて揃えば true。1 本でも使えなければ false(使えたものは置き換える) */
    private fun refreshOnce(): Boolean {
        val manifest = try {
            fetcher.fetchCueManifest()
        } catch (e: BridgeException) {
            if (e.kind == BridgeFailure.NOT_FOUND) publish(emptyMap()) // ホストが挨拶を配っていない: チャイムにする
            return false
        }
        val old = cache
        val next = HashMap<String, Cached>()
        var complete = true
        for (name in StartCue.NAMES) {
            if (isClosed()) return false
            val entry = manifest.cues[name]
            if (entry == null) { complete = false; continue }
            val prev = old[name]
            if (prev != null && prev.sha256 == entry.sha256) { next[name] = prev; continue }
            val wav = try { fetcher.fetchCue(name) } catch (_: Exception) { null }
            if (wav == null || !acceptable(wav, entry)) { complete = false; continue }
            next[name] = Cached(entry.sha256, wav)
        }
        publish(next)
        return complete
    }

    private fun isClosed() = synchronized(lock) { closed }

    private fun publish(map: Map<String, Cached>) {
        synchronized(lock) { if (!closed) cache = map }
    }

    companion object {
        /** 本番: 接続先の [BridgeClient] と、自分だけのスレッド(daemon。使わないあいだは消える) */
        fun create(settings: DaidaiSettings, nowMs: () -> Long): CueStore {
            // 1 本だけ。使わないあいだはスレッドを残さない(30 秒で消える)
            val executor: ExecutorService = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
                Thread(r, "daidai-cues").apply { isDaemon = true }
            }
            return CueStore(BridgeClient(settings.url, settings.token), { executor.execute(it) }, nowMs, onClose = { executor.shutdownNow() })
        }

        /** 使ってよい挨拶か: 目録の sha256 と一致し、WAV として正しく、実データも宣言も 5 秒以下 */
        fun acceptable(wav: ByteArray, entry: CueEntry): Boolean {
            if (wav.isEmpty() || wav.size > VoiceLimits.CUE_MAX_BYTES) return false
            if (entry.ms !in 1..VoiceLimits.CUE_MAX_MS) return false
            if (sha256Hex(wav) != entry.sha256) return false
            val info = try { WavValidator.validate(wav) } catch (_: WavFormatException) { return false }
            // ミリ秒に丸める前の積で比べる(丸めると 5 秒をわずかに超える WAV が通る)
            return info.dataBytes.toLong() * 1000 <= VoiceLimits.CUE_MAX_MS * info.sampleRate.toLong() * 2
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
