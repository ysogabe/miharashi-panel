package io.github.ysogabe.miharashi.panel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.Button
import java.util.Locale
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * ホストの URL とトークンの入力。最初の実装は手入力(設計書 §5.5)。
 * 長いトークンは、欄を選んでから `adb shell input text '<token>'` で入れると楽。
 */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var sleep: EditText
    private lateinit var result: TextView
    private lateinit var autoBrightness: CheckBox
    private lateinit var darkOff: CheckBox
    private lateinit var darkLux: EditText
    private lateinit var sensorStatus: TextView
    private lateinit var light: LightSensorReader
    private lateinit var bridgeUrl: EditText
    private lateinit var bridgeToken: EditText
    private lateinit var bridgeResult: TextView
    private lateinit var autoSwitch: CheckBox
    private lateinit var gateLux: EditText
    private lateinit var gateSeconds: EditText
    private lateinit var autoListen: CheckBox
    private lateinit var daidaiIdle: EditText
    private lateinit var autoSwitchStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        fun caption(s: String) = TextView(this).apply {
            text = s
            setTextColor(Color.rgb(132, 146, 162))
            textSize = 14f
        }
        root.addView(caption("キオスクの URL"))
        url = EditText(this).apply {
            setText(settings.url.ifEmpty { "http://" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        root.addView(url, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(caption("トークン(/etc/miharashi/panel.token)"))
        token = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.token)
            setTextColor(Color.WHITE)
            isSingleLine = true
            // setSingleLine は変換を上書きするので、パスワードのマスクを最後に指定する。
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        root.addView(token, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(caption("時計表示までの分(0 = 無効)"))
        sleep = EditText(this).apply {
            setText(settings.sleepMinutes.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        root.addView(sleep, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        sensorStatus = caption("照度センサーを確認中…")
        root.addView(sensorStatus)
        light = LightSensorReader(this) { lux ->
            sensorStatus.text = when {
                light.name == null -> "照度センサー: 利用不可（従来の明るさで動作）"
                !light.registered -> "照度センサー: 読み取り不可（従来の明るさで動作）"
                lux == null -> "照度センサー: ${light.name} / 有効な値を待機中"
                else -> String.format(Locale.ROOT, "照度センサー: %s / 現在 %.2f lx", light.name, lux)
            }
        }
        autoBrightness = CheckBox(this).apply {
            text = "照度に合わせてShow 5の明るさ・コントラストを調整"
            setTextColor(Color.WHITE)
            isChecked = settings.autoBrightness
        }
        root.addView(autoBrightness)
        root.addView(caption("操作盤・時計の明るさを5〜100%に調整し、明るい場所ではコントラストも上げます。設定画面はシステムの明るさです。"))
        darkOff = CheckBox(this).apply {
            text = "真っ暗な状態が30秒続いたらmiharashiを消灯"
            setTextColor(Color.WHITE)
            isChecked = settings.darkOff
        }
        root.addView(darkOff)
        root.addView(caption("本体が「自動」のときだけ消灯します。「点灯」「消灯」の手動指定を優先します。"))
        root.addView(caption("暗闇とみなす照度（0〜100 lx、初期値1 lx以下）"))
        darkLux = EditText(this).apply {
            setText(settings.darkLux.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        root.addView(darkLux, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(caption("明るくなっても自動点灯はしません。再点灯は操作盤の「点灯」か「自動」で行います。"))
        root.addView(caption("手動操作後は一度明るくなるまで自動消灯を抑止します。再判定は最大（5 lx、設定値＋4 lx）以上になった後です。"))

        addDaidaiSection(root, ::caption)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "接続テスト"
            setOnClickListener { test() }
        })
        buttons.addView(Button(this).apply {
            text = "保存して戻る"
            setOnClickListener { save() }
        })
        buttons.addView(Button(this).apply {
            text = "戻る"
            setOnClickListener { finish() }
        })
        root.addView(buttons)
        result = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        root.addView(result)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 「だいだい連携」の節(設計書 §8)。 */
    private fun addDaidaiSection(root: LinearLayout, caption: (String) -> TextView) {
        val d = settings.daidai
        fun field(initial: String, type: Int) = EditText(this).apply {
            setText(initial)
            inputType = type
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        root.addView(caption("── だいだい連携 ──").apply { textSize = 16f; setTextColor(Color.WHITE) })
        root.addView(caption("ブリッジの URL(空なら音声機能は無効)"))
        bridgeUrl = field(d.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            .apply { hint = "http://192.168.0.132:8800" }
        root.addView(bridgeUrl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(caption("ブリッジ専用のトークン(キオスクのトークンとは別の値)"))
        bridgeToken = field(d.token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            // setSingleLine は変換を上書きするので、パスワードのマスクを最後に指定する。
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        root.addView(bridgeToken, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "ブリッジの接続テスト"
            setOnClickListener { testBridge() }
        })
        bridgeResult = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        root.addView(bridgeResult)
        autoSwitch = CheckBox(this).apply {
            text = "だいだいの通知で自動的にだいだい画面へ切り替える"
            setTextColor(Color.WHITE)
            isChecked = d.autoSwitch
        }
        root.addView(autoSwitch)
        root.addView(caption("暗さの閾値(0〜${Settings.MAX_GATE_DARK_LUX.toInt()} lx。これ以下が続くと自動切替しない)"))
        gateLux = field(d.gateDarkLux.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(gateLux, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(caption("暗闇の継続秒数(0〜${Settings.MAX_GATE_DARK_SECONDS})"))
        gateSeconds = field(d.gateDarkSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        root.addView(gateSeconds, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        autoListen = CheckBox(this).apply {
            text = "自動で切り替えたときも話を聞く(マイクを開く)"
            setTextColor(Color.WHITE)
            isChecked = d.autoListen
        }
        root.addView(autoListen)
        root.addView(caption("だいだい画面の無操作秒数(${Settings.MIN_IDLE_SECONDS}〜${Settings.MAX_IDLE_SECONDS}。過ぎると操作盤へ戻る)"))
        daidaiIdle = field(d.idleSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        root.addView(daidaiIdle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        autoSwitchStatus = caption("")
        root.addView(autoSwitchStatus)
    }

    /** 画面 B に入れるのは、ブリッジの URL とトークンが両方入っているときだけ。接続テストは別スレッドで `GET /v1/health`。 */
    private fun testBridge() {
        val u = Settings.normalizeDaidaiUrl(bridgeUrl.text.toString())
        val t = bridgeToken.text.toString().trim()
        val problem = when {
            u.isEmpty() -> "ブリッジの URL を入れる"
            else -> Settings.validateDaidaiUrl(u)
                ?: if (t.isEmpty()) "ブリッジのトークンを入れる" else Settings.validateDaidaiToken(t, token.text.toString())
        }
        if (problem != null) {
            bridgeResult.text = problem
            return
        }
        bridgeResult.text = "接続中…"
        // 送るのはブリッジ専用のトークンだけ(panel のトークンは渡さない)。結果は原因の分類だけを出す
        Thread {
            val msg = BridgeHealth(u, t).check().message
            runOnUiThread { bridgeResult.text = msg }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        autoSwitchStatus.text = "直近の自動切替の判定: ${AutoSwitchDiagnostics.line}"
        sensorStatus.text = light.name?.let { "照度センサー: $it / 値を待機中" } ?: "照度センサー: 利用不可"
        light.start()
    }

    override fun onPause() {
        light.stop()
        super.onPause()
    }

    private fun test() {
        val u = url.text.toString()
        val t = token.text.toString()
        Settings.validate(u, t)?.let {
            result.text = it
            return
        }
        result.text = "接続中…"
        Thread {
            val msg = try {
                val s = KioskClient(u, t.trim()).status()
                "OK: ${s.host} / ${s.currentScreen?.title ?: "-"}"
            } catch (e: Exception) {
                "NG: " + KioskSync.describe(e)
            }
            runOnUiThread { result.text = msg }
        }.start()
    }

    private fun save() {
        val u = url.text.toString()
        val t = token.text.toString()
        Settings.validate(u, t)?.let {
            result.text = it
            return
        }
        val minutes = Settings.parseSleepMinutes(sleep.text.toString()) ?: run {
            result.text = "時計表示までの分は 0〜${Settings.MAX_SLEEP_MINUTES} の整数"
            return
        }
        val threshold = Settings.parseDarkLux(darkLux.text.toString()) ?: run {
            result.text = "暗闇の照度は 0〜100 lx の数値"
            return
        }
        val gateThreshold = Settings.parseGateDarkLux(gateLux.text.toString()) ?: run {
            result.text = "自動切替の暗さの閾値は 0〜${Settings.MAX_GATE_DARK_LUX.toInt()} lx の数値"
            return
        }
        val gateSecs = Settings.parseGateDarkSeconds(gateSeconds.text.toString()) ?: run {
            result.text = "暗闇の継続秒数は 0〜${Settings.MAX_GATE_DARK_SECONDS} の整数"
            return
        }
        val idleSecs = Settings.parseIdleSeconds(daidaiIdle.text.toString()) ?: run {
            result.text = "だいだい画面の無操作秒数は ${Settings.MIN_IDLE_SECONDS}〜${Settings.MAX_IDLE_SECONDS} の整数"
            return
        }
        val daidai = DaidaiSettings(
            url = Settings.normalizeDaidaiUrl(bridgeUrl.text.toString()),
            token = bridgeToken.text.toString().trim(),
            autoSwitch = autoSwitch.isChecked,
            gateDarkLux = gateThreshold,
            gateDarkSeconds = gateSecs,
            autoListen = autoListen.isChecked,
            idleSeconds = idleSecs,
        )
        try {
            settings.save(PanelSettings(u.trim(), t.trim(), minutes, autoBrightness.isChecked, darkOff.isChecked, threshold, daidai))
        } catch (e: IllegalArgumentException) {
            // 範囲外・不正な URL・panel と同じブリッジのトークンは保存されない(メッセージにトークンの値は含まれない)
            result.text = e.message ?: "保存できない設定"
            return
        }
        finish()
    }
}
