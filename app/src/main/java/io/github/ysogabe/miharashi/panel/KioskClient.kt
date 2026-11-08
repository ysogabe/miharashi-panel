package io.github.ysogabe.miharashi.panel

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

/** キオスクが 2xx 以外を返した。 */
class KioskHttpException(val code: Int, message: String) : IOException(message)

/**
 * miharashi の panel API のクライアント。呼び出しはすべてブロックするので、UI スレッドから呼ばない。
 * `baseUrl` は `http://192.168.0.132:8790` の形(末尾の / は無くてよい)。
 */
class KioskClient(
    baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {
    private val base = baseUrl.trimEnd('/')

    @Volatile
    private var eventsConnection: HttpURLConnection? = null

    private fun open(path: String, timeout: Int = readTimeoutMs): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = timeout
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun HttpURLConnection.errorMessage(): String {
        val body = runCatching { errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val detail = body?.let { runCatching { org.json.JSONObject(it).optString("error") }.getOrNull() }
        return "HTTP $responseCode" + (detail?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
    }

    /** `GET /api/v1/status` */
    fun status(): KioskStatus {
        val c = open("/api/v1/status")
        try {
            if (c.responseCode != 200) throw KioskHttpException(c.responseCode, c.errorMessage())
            return KioskStatus.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** `POST /api/v1/input`。204 で戻る。それ以外は KioskHttpException。 */
    fun input(key: String) = post("/api/v1/input", "key", key)

    /** `POST /api/v1/display`。キオスクのモニターを auto / force_on / force_off にする。 */
    fun display(mode: String) = post("/api/v1/display", "mode", mode)

    /** `POST /api/v1/rotation`。キオスクの自動巡回を止めたままにする(off)・再開する(on)。 */
    fun rotation(mode: String) = post("/api/v1/rotation", "mode", mode)

    /** `POST /api/v1/screen`。その画面へ移る(メニューも閉じる)。`id` はキオスクの `screens` のもの */
    fun screen(id: String) = post("/api/v1/screen", "id", id)

    /** `{"<field>": "<value>"}` を送り、204 を待つ。画面の id はキオスクから受け取った値なので、JSON として組み立てる。 */
    private fun post(path: String, field: String, value: String) {
        val c = open(path)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(org.json.JSONObject().put(field, value).toString().toByteArray()) }
            if (c.responseCode != 204) throw KioskHttpException(c.responseCode, c.errorMessage())
        } finally {
            c.disconnect()
        }
    }

    /**
     * `GET /api/v1/events`(SSE)。状態が届くたびに `onStatus` を呼ぶ。接続が切れるか `closeEvents` で戻る。
     * キオスクは 15 秒ごとに keepalive を送るので、読み取りの待ちはそれより長くする。
     */
    fun events(onStatus: (KioskStatus) -> Unit) {
        val c = open("/api/v1/events", timeout = EVENTS_READ_TIMEOUT_MS)
        c.setRequestProperty("Accept", "text/event-stream")
        eventsConnection = c
        try {
            if (c.responseCode != 200) throw KioskHttpException(c.responseCode, c.errorMessage())
            val parser = SseParser()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                // 行・イベントの上限を超えたら SseLimitException(IOException)で接続を終える
                val lines = BoundedLineReader(r, SseParser.MAX_LINE_BYTES)
                while (true) {
                    val line = lines.readLine() ?: break
                    val ev = parser.next(line) ?: continue
                    if (ev.name == "status") onStatus(KioskStatus.parse(ev.data))
                }
            }
        } finally {
            eventsConnection = null
            c.disconnect()
        }
    }

    /** 別スレッドの `events` を止める。 */
    fun closeEvents() {
        eventsConnection?.disconnect()
    }

    companion object {
        const val EVENTS_READ_TIMEOUT_MS = 40_000
    }
}

/** SSE の 1 行・1 イベントが上限を超えた(設計書 §5.8)。接続を終えて再接続させる。メッセージに受信した内容は含めない。 */
class SseLimitException(message: String) : IOException(message)

/** SSE のイベント 1 つ。`id` はこのイベントの `id:` 行の値(無ければ null)。 */
data class SseEvent(val name: String, val data: String, val id: String?)

/**
 * SSE の行を読み、イベントが 1 つまとまったら返す。コメント(`:` で始まる行)は捨てる。
 * 1 行は 8 KB(UTF-8)、1 イベント(`event:` `id:` `data:` の行と改行)は 16 KB まで。超えたら [SseLimitException]。
 */
class SseParser {
    private var event = "message"
    private var eventId: String? = null
    private val data = StringBuilder()
    private var eventBytes = 0

    /** 最後に受け取った `id:`(`Last-Event-ID` に使う)。イベントが `data` を持たなくても更新される。無ければ null */
    var lastEventId: String? = null
        private set

    /** @throws SseLimitException 行またはイベントが上限を超えたとき */
    fun next(line: String): SseEvent? {
        if (line.isEmpty()) {
            eventId?.let { lastEventId = it }
            val out = if (data.isEmpty()) null else SseEvent(event, data.toString(), eventId)
            reset()
            return out
        }
        val bytes = utf8Length(line)
        if (bytes > MAX_LINE_BYTES) throw SseLimitException("SSE の 1 行が上限($MAX_LINE_BYTES バイト)を超えた")
        if (line.startsWith(":")) return null
        eventBytes += bytes + 1
        if (eventBytes > MAX_EVENT_BYTES) throw SseLimitException("SSE の 1 イベントが上限($MAX_EVENT_BYTES バイト)を超えた")
        val name = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (name) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // NUL を含む id は無視する(SSE の仕様)
            "id" -> if ('\u0000' !in value) eventId = value
        }
        return null
    }

    /** 従来の呼び出し口。イベント名と data の組を返す(`id:` は [next] / [lastEventId] で読む)。 */
    fun line(line: String): Pair<String, String>? = next(line)?.let { it.name to it.data }

    private fun reset() {
        event = "message"
        eventId = null
        data.setLength(0)
        eventBytes = 0
    }

    companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_EVENT_BYTES = 16 * 1024

        private fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.code < 0x80 -> n += 1
                    c.code < 0x800 -> n += 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        n += 4
                        i++
                    }
                    else -> n += 3
                }
                i++
            }
            return n
        }
    }
}

/**
 * 1 行ずつ読む。`BufferedReader.readLine()` と同じ行区切り(LF・CR・CRLF)だが、`maxChars` を超える行は
 * 読みきる前に [SseLimitException] にする(メモリを使い尽くされない)。
 */
internal class BoundedLineReader(private val reader: Reader, private val maxChars: Int) {
    private var skipLf = false

    /** 次の行。入力の終わりなら null。 */
    fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (skipLf) {
                skipLf = false
                if (c == '\n'.code) continue
            }
            when (c) {
                '\n'.code -> return sb.toString()
                '\r'.code -> {
                    skipLf = true
                    return sb.toString()
                }
            }
            if (sb.length >= maxChars) throw SseLimitException("SSE の 1 行が上限($maxChars 文字)を超えた")
            sb.append(c.toChar())
        }
    }
}

/** 再接続の間隔: 1、2、4 … 30 秒。成功したら最初から。 */
class Backoff(private val minMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var next = minMs

    fun nextDelayMs(): Long = next.also { next = (next * 2).coerceAtMost(maxMs) }

    fun reset() {
        next = minMs
    }
}
