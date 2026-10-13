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
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}

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

    fun withStatus(s: KioskStatus, nowMs: Long) = copy(status = s, lastOkAtMs = nowMs, lastError = null)

    fun withError(message: String) = copy(lastError = message)

    companion object {
        const val FRESH_MS = 5_000L
        const val STALE_MS = 15_000L
    }
}
