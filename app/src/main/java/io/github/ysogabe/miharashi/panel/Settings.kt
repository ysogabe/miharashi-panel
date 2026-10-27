package io.github.ysogabe.miharashi.panel

import android.content.Context

/** 端末に保存するのは設定だけ(設計書 §5.6)。表示の状態は保存しない。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_URL, v.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    val configured get() = validate(url, token) == null

    /** 触らずにこの分数が過ぎたら時計を表示する。0 なら無効 */
    var sleepMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_MINUTES, DEFAULT_SLEEP_MINUTES)
        set(v) = prefs.edit().putInt(KEY_SLEEP_MINUTES, v.coerceIn(0, MAX_SLEEP_MINUTES)).apply()

    val autoBrightness: Boolean get() = prefs.getBoolean(KEY_AUTO_BRIGHTNESS, true)
    val darkOff: Boolean get() = prefs.getBoolean(KEY_DARK_OFF, true)
    val darkLux: Float get() = prefs.getFloat(KEY_DARK_LUX, DEFAULT_DARK_LUX)
        .takeIf { it.isFinite() && it in 0f..100f } ?: DEFAULT_DARK_LUX

    /** 入力検証後、一度の編集で全設定を保存する。 */
    fun save(url: String, token: String, minutes: Int, autoBrightness: Boolean, darkOff: Boolean, darkLux: Float) {
        require(validate(url, token) == null)
        require(minutes in 0..MAX_SLEEP_MINUTES)
        require(darkLux.isFinite() && darkLux in 0f..100f)
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_TOKEN, token.trim())
            .putInt(KEY_SLEEP_MINUTES, minutes).putBoolean(KEY_AUTO_BRIGHTNESS, autoBrightness)
            .putBoolean(KEY_DARK_OFF, darkOff).putFloat(KEY_DARK_LUX, darkLux).apply()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_SLEEP_MINUTES = "sleep_minutes"
        private const val KEY_AUTO_BRIGHTNESS = "auto_brightness"
        private const val KEY_DARK_OFF = "dark_off"
        private const val KEY_DARK_LUX = "dark_lux"
        const val DEFAULT_DARK_LUX = 1f

        fun parseDarkLux(s: String): Float? = s.trim().toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..100f }

        const val DEFAULT_SLEEP_MINUTES = 3
        const val MAX_SLEEP_MINUTES = 240

        /** 時計表示までの分数の入力。0〜240 の整数なら値、それ以外は null */
        fun parseSleepMinutes(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 0..MAX_SLEEP_MINUTES }

        /** 入力の確認。問題なければ null、あれば理由。 */
        fun validate(url: String, token: String): String? {
            val u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                return "URL は http://192.168.0.132:8790 の形で入れる"
            }
            if (u.removePrefix("http://").removePrefix("https://").trimEnd('/').isEmpty()) {
                return "URL にホストが無い"
            }
            if (token.trim().length < 16) return "トークンは 16 文字以上(キオスクの /etc/miharashi/panel.token)"
            return null
        }
    }
}
