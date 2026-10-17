package io.github.ysogabe.miharashi.panel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
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
            setText(settings.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            isSingleLine = true
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
        settings.url = u
        settings.token = t
        settings.sleepMinutes = minutes
        finish()
    }
}
