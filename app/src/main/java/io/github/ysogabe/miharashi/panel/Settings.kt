package io.github.ysogabe.miharashi.panel

import android.content.Context
import java.net.MalformedURLException
import java.net.URL

/** だいだい連携の設定(設計書 §5.1)。既定値は「連携なし・自動切替あり」。 */
data class DaidaiSettings(
    /** ブリッジの接続先。空なら音声機能は無効。保存時に正規化される(末尾の `/` なし) */
    val url: String = "",
    /** ブリッジ専用のトークン。panel のトークンと同じ値は保存できない。ログや例外メッセージに出さない */
    val token: String = "",
    val autoSwitch: Boolean = true,
    val gateDarkLux: Float = Settings.DEFAULT_GATE_DARK_LUX,
    val gateDarkSeconds: Int = Settings.DEFAULT_GATE_DARK_SECONDS,
    val autoListen: Boolean = true,
    val idleSeconds: Int = Settings.DEFAULT_IDLE_SECONDS,
) {
    override fun toString() = "DaidaiSettings(url=$url, token=${if (token.isEmpty()) "" else "***"}, autoSwitch=$autoSwitch, " +
        "gateDarkLux=$gateDarkLux, gateDarkSeconds=$gateDarkSeconds, autoListen=$autoListen, idleSeconds=$idleSeconds)"
}

/** 端末に保存する設定すべて。`Settings.save` に一括で渡す。 */
data class PanelSettings(
    val url: String,
    val token: String,
    val sleepMinutes: Int,
    val autoBrightness: Boolean,
    val darkOff: Boolean,
    val darkLux: Float,
    val daidai: DaidaiSettings = DaidaiSettings(),
) {
    override fun toString() = "PanelSettings(url=$url, token=***, sleepMinutes=$sleepMinutes, autoBrightness=$autoBrightness, " +
        "darkOff=$darkOff, darkLux=$darkLux, daidai=$daidai)"
}

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

    /**
     * だいだい連携の設定。保存されている値が範囲外・不正なとき(`install.sh` が直接書いた場合など)は、その項目だけ既定値に戻す。
     * トークンが panel のトークンと同じなら、安全側に倒して未設定として扱う(panel のトークンをブリッジへ送らない)。
     */
    val daidai: DaidaiSettings
        get() {
            val panelToken = token
            val daidaiToken = prefs.string(KEY_DAIDAI_TOKEN).trim()
            return DaidaiSettings(
                url = prefs.string(KEY_DAIDAI_URL).trim().let { if (validateDaidaiUrl(it) == null) normalizeDaidaiUrl(it) else "" },
                token = if (validateDaidaiToken(daidaiToken, panelToken) == null) daidaiToken else "",
                autoSwitch = prefs.boolean(KEY_DAIDAI_AUTO_SWITCH, true),
                gateDarkLux = prefs.float(KEY_DAIDAI_GATE_DARK_LUX, DEFAULT_GATE_DARK_LUX)
                    .takeIf { it.isFinite() && it in 0f..MAX_GATE_DARK_LUX } ?: DEFAULT_GATE_DARK_LUX,
                gateDarkSeconds = prefs.int(KEY_DAIDAI_GATE_DARK_SECONDS, DEFAULT_GATE_DARK_SECONDS)
                    .takeIf { it in 0..MAX_GATE_DARK_SECONDS } ?: DEFAULT_GATE_DARK_SECONDS,
                autoListen = prefs.boolean(KEY_DAIDAI_AUTO_LISTEN, true),
                idleSeconds = prefs.int(KEY_DAIDAI_IDLE_SECONDS, DEFAULT_IDLE_SECONDS)
                    .takeIf { it in MIN_IDLE_SECONDS..MAX_IDLE_SECONDS } ?: DEFAULT_IDLE_SECONDS,
            )
        }

    /** 今保存されている設定すべて。 */
    fun load(): PanelSettings = PanelSettings(url, token, sleepMinutes, autoBrightness, darkOff, darkLux, daidai)

    /**
     * 入力検証後、一度の編集で全設定を保存する。検証に通らなければ何も保存せず [IllegalArgumentException]
     * (メッセージにトークンの値は含まない)。だいだい連携の設定は今の値をそのまま残す。
     * 残っただいだいのトークンが新しい panel のトークンと同じになる場合も拒否する。
     */
    fun save(url: String, token: String, minutes: Int, autoBrightness: Boolean, darkOff: Boolean, darkLux: Float) =
        save(PanelSettings(url, token, minutes, autoBrightness, darkOff, darkLux, daidai))

    /** 入力検証後、一度の編集で全設定を保存する。検証に通らなければ何も保存せず [IllegalArgumentException]。 */
    fun save(s: PanelSettings) {
        validate(s)?.let { throw IllegalArgumentException(it) }
        val d = s.daidai
        prefs.edit().putString(KEY_URL, s.url.trim()).putString(KEY_TOKEN, s.token.trim())
            .putInt(KEY_SLEEP_MINUTES, s.sleepMinutes).putBoolean(KEY_AUTO_BRIGHTNESS, s.autoBrightness)
            .putBoolean(KEY_DARK_OFF, s.darkOff).putFloat(KEY_DARK_LUX, s.darkLux)
            .putString(KEY_DAIDAI_URL, normalizeDaidaiUrl(d.url)).putString(KEY_DAIDAI_TOKEN, d.token.trim())
            .putBoolean(KEY_DAIDAI_AUTO_SWITCH, d.autoSwitch).putFloat(KEY_DAIDAI_GATE_DARK_LUX, d.gateDarkLux)
            .putInt(KEY_DAIDAI_GATE_DARK_SECONDS, d.gateDarkSeconds).putBoolean(KEY_DAIDAI_AUTO_LISTEN, d.autoListen)
            .putInt(KEY_DAIDAI_IDLE_SECONDS, d.idleSeconds).apply()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_SLEEP_MINUTES = "sleep_minutes"
        private const val KEY_AUTO_BRIGHTNESS = "auto_brightness"
        private const val KEY_DARK_OFF = "dark_off"
        private const val KEY_DARK_LUX = "dark_lux"
        private const val KEY_DAIDAI_URL = "daidai_url"
        private const val KEY_DAIDAI_TOKEN = "daidai_token"
        private const val KEY_DAIDAI_AUTO_SWITCH = "daidai_auto_switch"
        private const val KEY_DAIDAI_GATE_DARK_LUX = "daidai_gate_dark_lux"
        private const val KEY_DAIDAI_GATE_DARK_SECONDS = "daidai_gate_dark_seconds"
        private const val KEY_DAIDAI_AUTO_LISTEN = "daidai_auto_listen"
        private const val KEY_DAIDAI_IDLE_SECONDS = "daidai_idle_seconds"
        const val DEFAULT_DARK_LUX = 1f

        const val DEFAULT_GATE_DARK_LUX = 1f
        const val MAX_GATE_DARK_LUX = 100f
        const val DEFAULT_GATE_DARK_SECONDS = 30
        const val MAX_GATE_DARK_SECONDS = 600
        const val DEFAULT_IDLE_SECONDS = 60
        const val MIN_IDLE_SECONDS = 10
        const val MAX_IDLE_SECONDS = 600
        const val MIN_TOKEN_LENGTH = 16

        fun parseDarkLux(s: String): Float? = s.trim().toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..100f }

        const val DEFAULT_SLEEP_MINUTES = 3
        const val MAX_SLEEP_MINUTES = 240

        /** 時計表示までの分数の入力。0〜240 の整数なら値、それ以外は null */
        fun parseSleepMinutes(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 0..MAX_SLEEP_MINUTES }

        /** 暗闇とみなす照度の入力。0〜100 の数値なら値、それ以外は null */
        fun parseGateDarkLux(s: String): Float? = s.trim().toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..MAX_GATE_DARK_LUX }

        /** 暗闇がこの秒数続いたら見送る、の入力。0〜600 の整数なら値、それ以外は null */
        fun parseGateDarkSeconds(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 0..MAX_GATE_DARK_SECONDS }

        /** B で無操作のとき A へ戻すまでの秒数の入力。10〜600 の整数なら値、それ以外は null */
        fun parseIdleSeconds(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in MIN_IDLE_SECONDS..MAX_IDLE_SECONDS }

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

        /** 設定すべての確認(保存の直前に使う)。問題なければ null、あれば理由(トークンの値は含まない)。 */
        fun validate(s: PanelSettings): String? {
            validate(s.url, s.token)?.let { return it }
            if (s.sleepMinutes !in 0..MAX_SLEEP_MINUTES) return "時計表示までの分は 0〜$MAX_SLEEP_MINUTES の整数"
            if (!(s.darkLux.isFinite() && s.darkLux in 0f..100f)) return "暗闇の照度は 0〜100 lx の数値"
            val d = s.daidai
            validateDaidaiUrl(d.url)?.let { return it }
            validateDaidaiToken(d.token, s.token)?.let { return it }
            if (!(d.gateDarkLux.isFinite() && d.gateDarkLux in 0f..MAX_GATE_DARK_LUX)) return "自動切替の暗闇の照度は 0〜100 lx の数値"
            if (d.gateDarkSeconds !in 0..MAX_GATE_DARK_SECONDS) return "自動切替の暗闇の秒数は 0〜$MAX_GATE_DARK_SECONDS の整数"
            if (d.idleSeconds !in MIN_IDLE_SECONDS..MAX_IDLE_SECONDS) {
                return "だいだい画面の無操作の秒数は $MIN_IDLE_SECONDS〜$MAX_IDLE_SECONDS の整数"
            }
            return null
        }

        private val HOST = Regex("""[A-Za-z0-9._-]+|\[[0-9A-Fa-f:.]+]""")

        /**
         * `daidai_url` の確認(設計書 §5.1.1)。空は「未設定」として許す。
         * `java.net.URL` として読め、scheme が http / https、ホストがあり、ユーザー情報・クエリ・フラグメントが無いものだけ。パスの接頭辞は許す。
         * 問題なければ null、あれば理由。
         */
        fun validateDaidaiUrl(url: String): String? {
            val u = url.trim()
            if (u.isEmpty()) return null
            val form = "ブリッジの URL は http://192.168.0.132:8800 の形で入れる"
            if (u.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return form
            val lower = u.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return form
            if ('?' in u) return "ブリッジの URL にクエリは付けられない"
            if ('#' in u) return "ブリッジの URL にフラグメントは付けられない"
            val parsed = try {
                URL(u)
            } catch (_: MalformedURLException) {
                return form
            }
            val authority = parsed.authority.orEmpty()
            if ('@' in authority || parsed.userInfo != null) return "ブリッジの URL にユーザー情報は付けられない"
            val host = parsed.host.orEmpty()
            if (host.isEmpty()) return "ブリッジの URL にホストが無い"
            if (!HOST.matches(host)) return form
            // URL.port は未指定も「:-1」も -1 になるので、authority の文字列でポートの明示を見分ける
            val hostPort = authority.substringAfterLast(']', authority) // IPv6 リテラルの `]` の後ろだけを見る
            if (':' in hostPort) {
                val portText = hostPort.substringAfterLast(':')
                if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return "ブリッジの URL のポートが正しくない"
                if (portText.length > 5 || portText.toInt() !in 1..65535) return "ブリッジの URL のポートが範囲外"
            }
            if (parsed.port != -1 && parsed.port !in 1..65535) return "ブリッジの URL のポートが範囲外"
            return null
        }

        /** 検証済みの `daidai_url` の正規形(前後の空白と、末尾の `/` を除く)。空は空のまま。 */
        fun normalizeDaidaiUrl(url: String): String = url.trim().trimEnd('/')

        /**
         * `daidai_token` の確認。空は「未設定」として許す。空でなければ可視 ASCII(0x21〜0x7E)だけで 16 文字以上、
         * panel のトークンと同じ値は拒否する(`Authorization` ヘッダにそのまま載るので、内部の空白・改行・制御文字・非 ASCII は
         * ヘッダ設定の例外や、その例外メッセージ経由の秘密の漏れのもとになる)。理由の文言にトークンの値は含めない。問題なければ null、あれば理由。
         */
        fun validateDaidaiToken(daidaiToken: String, panelToken: String): String? {
            val t = daidaiToken.trim()
            if (t.isEmpty()) return null
            if (!isBridgeTokenCharset(t)) return "ブリッジのトークンに使えるのは英数字と記号だけ(空白・改行・日本語は不可)"
            if (t.length < MIN_TOKEN_LENGTH) return "ブリッジのトークンは $MIN_TOKEN_LENGTH 文字以上"
            if (t == panelToken.trim()) return "ブリッジのトークンは panel のトークンと別の値にする"
            return null
        }

        /** ブリッジのトークンの文字種。可視 ASCII(0x21〜0x7E)だけ。空文字列は true。 */
        fun isBridgeTokenCharset(token: String): Boolean = token.all { it in '!'..'~' }

        private fun android.content.SharedPreferences.string(key: String): String =
            try { getString(key, "") ?: "" } catch (_: ClassCastException) { "" }

        private fun android.content.SharedPreferences.boolean(key: String, def: Boolean): Boolean =
            try { getBoolean(key, def) } catch (_: ClassCastException) { def }

        private fun android.content.SharedPreferences.int(key: String, def: Int): Int =
            try { getInt(key, def) } catch (_: ClassCastException) { def }

        private fun android.content.SharedPreferences.float(key: String, def: Float): Float =
            try { getFloat(key, def) } catch (_: ClassCastException) { def }
    }
}
