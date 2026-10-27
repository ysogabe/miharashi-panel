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
            text = "照度に合わせてShow 5の明るさを調整"
            setTextColor(Color.WHITE)
            isChecked = settings.autoBrightness
        }
        root.addView(autoBrightness)
        root.addView(caption("操作盤は5〜100%、待機時計は5%。設定画面はシステムの明るさを使います。"))
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

    override fun onResume() {
        super.onResume()
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
        settings.save(u, t, minutes, autoBrightness.isChecked, darkOff.isChecked, threshold)
        finish()
    }
}
