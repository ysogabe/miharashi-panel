package io.github.ysogabe.miharashi.panel

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 接続テストの結果の分類(設計書 §5.2.7)。原因の分類だけで、トークンや応答の本文は持たない。 */
enum class HealthStatus {
    /** `200` で `{"ok":true,...}` */
    OK,

    /** `401` / `403`(ブリッジ専用トークンが違う) */
    UNAUTHORIZED,

    /** 接続できない・タイムアウト・途中で切れた */
    UNREACHABLE,

    /** リダイレクト(3xx)、5xx など、200 以外。または本文が 4 KB 超・JSON でない・`ok` が true でない */
    BAD_RESPONSE,

    /** URL かトークンが未設定・不正(トークンに使えない文字を含む場合を含む)で、通信しなかった */
    NOT_CONFIGURED,
}

/**
 * 接続テストの結果。`httpCode` は `BAD_RESPONSE` のときの HTTP ステータス(分かれば)。
 * `asr` / `tts` / `daidai` は `OK` のときの応答の項目(真偽値でなければ null)。
 */
data class HealthResult(
    val status: HealthStatus,
    val httpCode: Int? = null,
    val asr: Boolean? = null,
    val tts: Boolean? = null,
    val daidai: Boolean? = null,
) {
    /** 画面に出す文(原因の分類だけ。トークンや応答の本文は含まない) */
    val message: String
        get() = when (status) {
            HealthStatus.OK -> "OK: ブリッジに接続できた" + notReady().let { if (it.isEmpty()) "" else "（${it.joinToString("・")} は未準備）" }
            HealthStatus.UNAUTHORIZED -> "NG: 認証に失敗(ブリッジ専用トークンを確認)"
            HealthStatus.UNREACHABLE -> "NG: ブリッジに接続できない"
            HealthStatus.BAD_RESPONSE -> "NG: 応答が正しくない" + (httpCode?.let { " (HTTP $it)" } ?: "")
            HealthStatus.NOT_CONFIGURED -> "NG: ブリッジの URL とトークンを確認"
        }

    private fun notReady() = buildList {
        if (asr == false) add("音声認識")
        if (tts == false) add("音声合成")
        if (daidai == false) add("だいだい")
    }
}

/**
 * ブリッジの接続テスト(`GET <daidai_url>/v1/health`、設計書 §5.2.7)。Android に依存しない。呼び出しはブロックするので UI スレッドから呼ばない。
 *
 * - URL は検証済みの `daidai_url` に固定パス `/v1/health` を足して自分で組む。
 * - 送るのは `Authorization: Bearer <ブリッジ専用トークン>` だけ。**panel のトークンは渡されない**(この型はそれを知らない)。
 * - 接続 3 秒・読み取り 5 秒。さらに接続前から数えた全体の期限(既定 8 秒)で、ウォッチドッグが接続を切る(超えたら [HealthStatus.UNREACHABLE])。
 *   リダイレクト(3xx)には追従せずエラー。応答本文は 4 KB まで。
 * - トークンや応答の本文を logcat・例外メッセージ・結果に出さない。接続の生成・ヘッダ設定で起きた例外も、メッセージを見ずに分類だけする。
 */
class BridgeHealth(
    baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = BridgeHttp.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = BridgeHttp.READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = BridgeHttp.TOTAL_TIMEOUT_MS,
) {
    private val base = baseUrl.trim()

    fun check(): HealthResult {
        val t = token.trim()
        if (base.isEmpty() || t.isEmpty() || Settings.validateDaidaiUrl(base) != null) {
            return HealthResult(HealthStatus.NOT_CONFIGURED)
        }
        val c = try {
            BridgeHttp.open(base, "/v1/health", t, connectTimeoutMs, readTimeoutMs)
        } catch (_: IOException) {
            return HealthResult(HealthStatus.NOT_CONFIGURED)
        } catch (_: RuntimeException) {
            // ヘッダ設定の IllegalArgumentException など。メッセージにはトークンが含まれ得るので見ない・出さない
            return HealthResult(HealthStatus.NOT_CONFIGURED)
        }
        return try {
            BridgeHttp.withDeadline(c, totalTimeoutMs) {
                when (val code = c.responseCode) {
                    200 -> parse(c)
                    401, 403 -> HealthResult(HealthStatus.UNAUTHORIZED, code)
                    else -> HealthResult(HealthStatus.BAD_RESPONSE, code)
                }
            }
        } catch (_: IOException) {
            HealthResult(HealthStatus.UNREACHABLE)
        } catch (_: RuntimeException) {
            HealthResult(HealthStatus.BAD_RESPONSE)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(c: HttpURLConnection): HealthResult {
        val bad = HealthResult(HealthStatus.BAD_RESPONSE, 200)
        if (c.contentLengthLong > BridgeHttp.MAX_BODY_BYTES) return bad
        val body = BridgeHttp.readBounded(c.inputStream, BridgeHttp.MAX_BODY_BYTES) ?: return bad
        val o = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return bad
        }
        if (o.opt("ok") != true) return bad
        fun flag(key: String) = o.opt(key) as? Boolean
        return HealthResult(HealthStatus.OK, 200, flag("asr"), flag("tts"), flag("daidai"))
    }
}

/**
 * ブリッジへの HTTP の共通規則(設計書 §5.2.0)。接続テストと、後の `BridgeClient` が同じ規則を使う:
 * URL は検証済みの `daidai_url` に固定パスを足して自分で組む、リダイレクトに追従しない、ブリッジ専用トークンだけを送る、本文に上限、
 * 接続前から数える全体の期限([withDeadline])。
 */
internal object BridgeHttp {
    const val CONNECT_TIMEOUT_MS = 3_000
    const val READ_TIMEOUT_MS = 5_000

    /** 接続+ヘッダ+本文の全体の期限(接続前から数える) */
    const val TOTAL_TIMEOUT_MS = 8_000L

    /** 応答本文の上限(エラー応答・health とも 4 KB) */
    const val MAX_BODY_BYTES = 4 * 1024

    /** ウォッチドッグのスレッド名(テストが残りを数える) */
    const val WATCHDOG_THREAD_NAME = "BridgeHttp-watchdog"

    /**
     * 接続を開く(まだ通信しない)。`path` は呼び出し側の固定テンプレートで、ホストの応答に含まれる値を入れない。
     * `baseUrl` は検証済みのもの(末尾の `/` は除く)。リダイレクトには追従しない。
     * トークンが可視 ASCII でないときは、ヘッダ設定の例外(メッセージに値が入る)を避けるため、接続を作らず固定文言の [IOException]。
     * 呼び出し側は [IOException] と [RuntimeException] のメッセージを見ずに分類する。
     */
    @Throws(IOException::class)
    fun open(baseUrl: String, path: String, token: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        if (token.isEmpty() || !Settings.isBridgeTokenCharset(token)) throw IOException("invalid bridge token")
        return (URL(Settings.normalizeDaidaiUrl(baseUrl) + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
    }

    /**
     * 最大 `limit` バイトまで読む。超えたら(`limit` を 1 バイトでも超える本文)null。
     * 全体の時間は [withDeadline] のウォッチドッグが見る(読み取りタイムアウトは 1 回の読みごとなので)。
     */
    fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > limit) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /**
     * `block`(`c` への通信:接続・ヘッダ・本文の読み取り)に全体の期限 `totalMs` を課す。呼び出しの前から数え、期限が来たら
     * 別スレッドのウォッチドッグが `c.disconnect()` で通信を打ち切る。期限が来ていたら、`block` が正常に返っても(EOF が来ても)
     * 例外で終わっても、結果を捨てて固定文言の [IOException]("deadline")を投げる。ウォッチドッグは必ず取り消され、スレッドは残らない。
     */
    @Throws(IOException::class)
    fun <T> withDeadline(c: HttpURLConnection, totalMs: Long, block: () -> T): T {
        val watchdog = Watchdog(c, totalMs)
        val value: T
        try {
            value = block()
        } catch (e: IOException) {
            throw if (watchdog.cancel()) IOException("deadline") else e
        } catch (e: RuntimeException) {
            throw if (watchdog.cancel()) IOException("deadline") else e
        } catch (e: Error) {
            watchdog.cancel()
            throw e
        }
        if (watchdog.cancel()) throw IOException("deadline")
        return value
    }

    /** 期限で `HttpURLConnection.disconnect()` を呼ぶ専用の軽いデーモンスレッド。[cancel] で止めて、終わるのを待つ。 */
    private class Watchdog(private val conn: HttpURLConnection, totalMs: Long) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val deadlineNanos = System.nanoTime() + totalMs.coerceAtLeast(0) * 1_000_000
        private var cancelled = false
        private var fired = false
        private val thread = Thread({ run() }, WATCHDOG_THREAD_NAME).apply { isDaemon = true; start() }

        private fun run() {
            lock.withLock {
                while (!cancelled) {
                    val remaining = deadlineNanos - System.nanoTime()
                    if (remaining <= 0) {
                        fired = true
                        break
                    }
                    changed.awaitNanos(remaining)
                }
            }
            if (fired) {
                try {
                    conn.disconnect()
                } catch (_: RuntimeException) {
                }
            }
        }

        /** 取り消す(期限が来る前なら、以後は切らない)。期限が先に来ていたら true。切断が終わるまで待ってからスレッドの終了も待つ。 */
        fun cancel(): Boolean {
            val expired = lock.withLock {
                cancelled = true
                changed.signalAll()
                fired
            }
            try {
                thread.join(2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return expired
        }
    }
}
