package io.github.ysogabe.miharashi.panel

import org.json.JSONObject

/** キオスクに登録されている画面 1 つ。 */
data class ScreenInfo(val id: String, val title: String)

/**
 * キオスクのモニター。`mode` は HA の手動オーバーライド(auto / force_on / force_off、未設定なら null)。
 * `control` が false なら、パネルからは切り替えられない。
 */
data class DisplayInfo(val on: Boolean, val mode: String?, val control: Boolean)

/**
 * キオスクの自動巡回(miharashi #45)。キーや画面の選択で止まり、時間がたつと戻る。
 * `resumeInS` は止まっているときの、受け取った時点での巡回に戻るまでの秒数(分からなければ null)。
 */
data class RotationInfo(val paused: Boolean, val resumeInS: Long?)

/** `/api/v1/input` に送る 6 つのキーの名前(キオスクの `keys` の項目名と同じ)。 */
val KEY_NAMES = listOf("prev", "next", "select", "up", "down", "menu")

/**
 * miharashi の `GET /api/v1/status` を写した不変オブジェクト(miharashi docs/panel/DESIGN.md §5.2)。
 * 表示はすべてここから組み立て直せる。アプリは状態を持たない。
 */
data class KioskStatus(
    val schemaVersion: Int,
    val host: String,
    val screens: List<ScreenInfo>,
    /** 表示中の画面の `screens` の添字。分からなければ null */
    val current: Int?,
    /** 画面の中のページ名(状況画面の OVERVIEW / DETAILS) */
    val page: String?,
    /** 写真の (何枚目, 全体の枚数) */
    val photo: Pair<Int, Int>?,
    val feedOnline: Boolean,
    val uptimeSeconds: Long,
    /** 古いキオスク(display を返さない)では null */
    val display: DisplayInfo? = null,
    /** 巡回が無効か、古いキオスク(rotation を返さない)では null */
    val rotation: RotationInfo? = null,
    /** キオスクのメニューで選んでいる画面(`screens` の添字)。閉じているか、古いキオスクでは null(miharashi #49) */
    val menu: Int? = null,
    /**
     * 今それぞれのキーを押すと何が起きるかのラベル(miharashi #51)。値が null のキーは押しても何もしない。
     * 古いキオスク(keys を返さない)では null で、パネルは汎用のラベルで出す
     */
    val keys: Map<String, String?>? = null,
) {
    val currentScreen: ScreenInfo? get() = current?.let { screens.getOrNull(it) }

    companion object {
        /** 読めるのはこの版まで。新しい版でも、知っている項目だけ読む。 */
        const val SUPPORTED_SCHEMA = 1

        /** @throws org.json.JSONException 形が違うとき */
        fun parse(json: String): KioskStatus {
            val o = JSONObject(json)
            val screens = o.getJSONArray("screens").let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    ScreenInfo(s.getString("id"), s.getString("title"))
                }
            }
            val screen = o.optJSONObject("screen")
            val photo = o.optJSONObject("photo")
            val display = o.optJSONObject("display")
            val rotation = o.optJSONObject("rotation")
            val menu = o.optJSONObject("menu")
            val keys = o.optJSONObject("keys")
            return KioskStatus(
                schemaVersion = o.getInt("schema_version"),
                host = o.optString("host", "-"),
                screens = screens,
                current = screen?.optInt("index", -1)?.takeIf { it in screens.indices },
                page = screen?.optStringOrNull("page"),
                photo = photo?.let { it.getInt("position") to it.getInt("total") },
                feedOnline = o.optString("feed") == "online",
                uptimeSeconds = o.optLong("uptime_s", 0),
                display = display?.let {
                    DisplayInfo(
                        on = it.optBoolean("on", true),
                        mode = it.optStringOrNull("mode"),
                        control = it.optBoolean("control", false),
                    )
                },
                // 知らない state は、巡回が無いものとして扱う
                rotation = when (rotation?.optString("state")) {
                    "on" -> RotationInfo(paused = false, resumeInS = null)
                    "paused" -> RotationInfo(
                        paused = true,
                        resumeInS = if (rotation.isNull("resume_in_s")) null else rotation.optLong("resume_in_s").coerceAtLeast(0),
                    )
                    else -> null
                },
                // 添字と id が食い違えば id を信じる。どちらも見つからなければ閉じているものとして扱う
                menu = menu?.let { m ->
                    m.optInt("index", -1).takeIf { screens.getOrNull(it)?.id == m.optString("id") }
                        ?: screens.indexOfFirst { it.id == m.optString("id") }.takeIf { it >= 0 }
                },
                keys = keys?.let { k -> KEY_NAMES.associateWith { k.optStringOrNull(it) } },
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}

/** 秒を `m:ss` にする(巡回に戻るまでの残り)。 */
fun formatMinSec(secs: Long): String = String.format(java.util.Locale.ROOT, "%d:%02d", secs / 60, secs % 60)

/** ヘッダーの `API` の点灯。直近 5 秒以内に取れたら FRESH、15 秒までは STALE、それより古いと OFFLINE。 */
enum class Freshness { FRESH, STALE, OFFLINE }

/** 画面に出すものすべて。`status` は最後に取れたもの(OFFLINE でも薄く残して出す)。 */
data class PanelState(
    val status: KioskStatus? = null,
    val lastOkAtMs: Long? = null,
    val lastError: String? = null,
) {
    fun freshness(nowMs: Long): Freshness {
        val age = lastOkAtMs?.let { nowMs - it } ?: return Freshness.OFFLINE
        return when {
            age <= FRESH_MS -> Freshness.FRESH
            age <= STALE_MS -> Freshness.STALE
            else -> Freshness.OFFLINE
        }
    }

    /**
     * 巡回に戻るまでの秒数(切り上げ、0 未満にしない)。止まっていないか分からなければ null。
     * キオスクは止める時間が変わったときしか送らないので、受け取ってからの時間を引いて数え下げる
     */
    fun rotationResumeInS(nowMs: Long): Long? {
        val secs = status?.rotation?.takeIf { it.paused }?.resumeInS ?: return null
        val at = lastOkAtMs ?: return null
        val leftMs = secs * 1_000 - (nowMs - at)
        return if (leftMs <= 0) 0 else (leftMs + 999) / 1_000
    }

    fun withStatus(s: KioskStatus, nowMs: Long) = copy(status = s, lastOkAtMs = nowMs, lastError = null)

    fun withError(message: String) = copy(lastError = message)

    companion object {
        const val FRESH_MS = 5_000L
        const val STALE_MS = 15_000L
    }
}
