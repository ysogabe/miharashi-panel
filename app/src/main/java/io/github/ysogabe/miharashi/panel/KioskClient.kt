package io.github.ysogabe.miharashi.panel

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
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
                while (true) {
                    val line = r.readLine() ?: break
                    val ev = parser.line(line) ?: continue
                    if (ev.first == "status") onStatus(KioskStatus.parse(ev.second))
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

/** SSE の行を読み、イベントが 1 つまとまったら (event 名, data) を返す。コメント(`:` で始まる行)は捨てる。 */
class SseParser {
    private var event = "message"
    private val data = StringBuilder()

    fun line(line: String): Pair<String, String>? {
        if (line.isEmpty()) {
            if (data.isEmpty()) {
                event = "message"
                return null
            }
            val out = event to data.toString()
            event = "message"
            data.setLength(0)
            return out
        }
        if (line.startsWith(":")) return null
        val name = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (name) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
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
