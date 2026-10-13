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

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"

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
