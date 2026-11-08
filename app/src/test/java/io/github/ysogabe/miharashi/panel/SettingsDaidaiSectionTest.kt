package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 設定画面の「だいだい連携」の節(AC11, AC13 の接続テスト, AC20, 診断の 1 行)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsDaidaiSectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val panelUrl = "http://192.168.0.132:8790"
    private val panelToken = "panel-token-0123456789"
    private val bridgeToken = "bridge-token-ABCDEFGHIJ"
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        AutoSwitchDiagnostics.clear()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        AutoSwitchDiagnostics.clear()
        server.shutdown()
    }

    private class Screen(val controller: ActivityController<SettingsActivity>) {
        val activity: SettingsActivity = controller.get()

        @Suppress("UNCHECKED_CAST")
        fun <T> field(name: String): T = SettingsActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity) as T

        fun edit(name: String) = field<EditText>(name)
        fun text(name: String) = field<TextView>(name).text.toString()
        fun check(name: String, on: Boolean) { field<CheckBox>(name).isChecked = on }
        fun set(name: String, value: String) = edit(name).setText(value)

        fun button(label: String): Button {
            fun walk(v: View): Button? {
                if (v is Button && v.text.toString() == label) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
                return null
            }
            return walk(activity.window.decorView) ?: error("button not found: $label")
        }

        fun click(label: String) { button(label).performClick() }

        /** 別スレッドの結果が UI に届くまで待つ */
        fun waitFor(name: String, notEqual: String = "接続中…") {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (text(name) == notEqual && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun finish() { controller.pause().stop().destroy() }
    }

    private fun open(prefs: android.content.SharedPreferences.Editor.() -> Unit = {}): Screen {
        context.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().apply(prefs).commit()
        return Screen(Robolectric.buildActivity(SettingsActivity::class.java).setup())
    }

    /** panel 側を有効な値にして、だいだい側は `edits` で入れる */
    private fun Screen.fillValid() {
        set("url", panelUrl)
        set("token", panelToken)
        set("sleep", "3")
        set("bridgeUrl", "http://192.168.0.132:8800/")
        set("bridgeToken", bridgeToken)
        check("autoSwitch", true)
        set("gateLux", "2.5")
        set("gateSeconds", "45")
        check("autoListen", false)
        set("daidaiIdle", "90")
    }

    // --- 保存 ---

    @Test fun validValuesAreSavedAndNormalized() {
        val s = open()
        try {
            s.fillValid()
            s.click("保存して戻る")
            assertTrue(s.activity.isFinishing)
            val d = Settings(context).daidai
            assertEquals("http://192.168.0.132:8800", d.url)
            assertEquals(bridgeToken, d.token)
            assertTrue(d.autoSwitch)
            assertEquals(2.5f, d.gateDarkLux, 0f)
            assertEquals(45, d.gateDarkSeconds)
            assertFalse(d.autoListen)
            assertEquals(90, d.idleSeconds)
            assertEquals("panel 側の設定も保存される", panelUrl, Settings(context).url)
        } finally { s.finish() }
    }

    @Test fun savedValuesAreShownWhenReopened() {
        val first = open()
        first.fillValid()
        first.click("保存して戻る")
        first.finish()
        val s = Screen(Robolectric.buildActivity(SettingsActivity::class.java).setup())
        try {
            assertEquals("http://192.168.0.132:8800", s.edit("bridgeUrl").text.toString())
            assertEquals(bridgeToken, s.edit("bridgeToken").text.toString())
            assertEquals("45", s.edit("gateSeconds").text.toString())
            assertEquals("90", s.edit("daidaiIdle").text.toString())
            assertFalse(s.field<CheckBox>("autoListen").isChecked)
        } finally { s.finish() }
    }

    @Test fun bridgeTokenIsMasked() {
        val s = open { putString("daidai_token", bridgeToken) }
        try {
            val f = s.edit("bridgeToken")
            assertTrue(f.transformationMethod is PasswordTransformationMethod)
            assertNotEquals(bridgeToken, f.transformationMethod.getTransformation(f.text, f).toString())
        } finally { s.finish() }
    }

    private fun assertRejected(s: Screen, snippet: String) {
        assertFalse("保存して閉じてはいけない: $snippet", s.activity.isFinishing)
        assertTrue("エラーを表示する: ${s.text("result")}", s.text("result").contains(snippet))
        val saved = Settings(context)
        assertEquals("何も保存されない", DaidaiSettings(), saved.daidai)
        assertEquals("", saved.url)
        assertEquals("", saved.token)
    }

    @Test fun outOfRangeNumbersAreRejectedAndNothingIsSaved() {
        val cases = listOf(
            Triple("gateLux", "100.5", "0〜100 lx"),
            Triple("gateLux", "-1", "0〜100 lx"),
            Triple("gateLux", "NaN", "0〜100 lx"),
            Triple("gateLux", "abc", "0〜100 lx"),
            Triple("gateSeconds", "601", "0〜600"),
            Triple("gateSeconds", "-1", "0〜600"),
            Triple("gateSeconds", "1.5", "0〜600"),
            Triple("daidaiIdle", "9", "10〜600"),
            Triple("daidaiIdle", "601", "10〜600"),
            Triple("daidaiIdle", "", "10〜600"),
        )
        for ((field, value, snippet) in cases) {
            val s = open()
            try {
                s.fillValid()
                s.set(field, value)
                s.click("保存して戻る")
                assertRejected(s, snippet)
            } finally { s.finish() }
        }
    }

    @Test fun boundaryValuesAreAccepted() {
        val s = open()
        try {
            s.fillValid()
            s.set("gateLux", "0"); s.set("gateSeconds", "0"); s.set("daidaiIdle", "10")
            s.click("保存して戻る")
            assertTrue(s.activity.isFinishing)
            val d = Settings(context).daidai
            assertEquals(0f, d.gateDarkLux, 0f)
            assertEquals(0, d.gateDarkSeconds)
            assertEquals(10, d.idleSeconds)
        } finally { s.finish() }
        val t = Screen(Robolectric.buildActivity(SettingsActivity::class.java).setup())
        try {
            t.fillValid()
            t.set("gateLux", "100"); t.set("gateSeconds", "600"); t.set("daidaiIdle", "600")
            t.click("保存して戻る")
            val d = Settings(context).daidai
            assertEquals(100f, d.gateDarkLux, 0f)
            assertEquals(600, d.gateDarkSeconds)
            assertEquals(600, d.idleSeconds)
        } finally { t.finish() }
    }

    @Test fun invalidBridgeUrlsAreRejected() {
        for (bad in listOf("ftp://192.168.0.132", "192.168.0.132:8800", "http://user:pw@192.168.0.132:8800",
            "http://192.168.0.132:8800/?x=1", "http://192.168.0.132:8800/#f", "http://", "http://host with space")) {
            val s = open()
            try {
                s.fillValid()
                s.set("bridgeUrl", bad)
                s.click("保存して戻る")
                assertRejected(s, "ブリッジの URL")
            } finally { s.finish() }
        }
    }

    @Test fun bridgeTokenSameAsPanelTokenOrTooShortIsRejected() {
        val same = open()
        try {
            same.fillValid()
            same.set("bridgeToken", panelToken)
            same.click("保存して戻る")
            assertRejected(same, "panel のトークンと別")
            assertFalse("エラー文にトークンを出さない", same.text("result").contains(panelToken))
        } finally { same.finish() }
        val short = open()
        try {
            short.fillValid()
            short.set("bridgeToken", "short")
            short.click("保存して戻る")
            assertRejected(short, "16 文字以上")
        } finally { short.finish() }
    }

    @Test fun emptyBridgeUrlAndTokenAreAllowedAndMeanUnconfigured() {
        val s = open()
        try {
            s.fillValid()
            s.set("bridgeUrl", ""); s.set("bridgeToken", "")
            s.click("保存して戻る")
            assertTrue(s.activity.isFinishing)
            assertFalse(Settings(context).daidai.isConfigured())
        } finally { s.finish() }
    }

    @Test fun savedChangesApplyFromTheNextJudgementWithoutRestart() {
        // コントローラは設定を毎回読む: 保存の前は 5 lx は明るい、保存の後は(閾値 10 lx・0 秒で)暗闇
        val settings = Settings(context)
        val pages = PageController()
        var now = 1_000L
        val c = DaidaiController(pages, { settings.daidai }, {}, {}, { now })
        c.start(PanelState(), "ep")
        c.onLux(5f)
        val base = javaClass.getResource("/fixtures/status-daidai-empty.json")!!.readText()
        fun status(rev: Long, id: String): KioskStatus = KioskStatus.parse(
            org.json.JSONObject(base).also {
                it.getJSONObject("daidai").put("rev", rev).put("notices", org.json.JSONArray().put(
                    org.json.JSONObject().put("id", id).put("title", "t").put("body", "b").put("age_s", 1)))
            }.toString())
        c.onKioskState(PanelState().withStatus(status(1, "a"), now))   // 基準
        c.onKioskState(PanelState().withStatus(status(2, "b"), now))
        assertEquals(Page.B, pages.targetPage)
        pages.animationEnded(now)
        pages.beginDrag(now); pages.endDrag(Page.A, now); pages.animationEnded(now)

        val s = open()
        try {
            s.fillValid()
            s.set("gateLux", "10"); s.set("gateSeconds", "0")
            s.click("保存して戻る")
        } finally { s.finish() }
        now += 1_000
        c.onKioskState(PanelState().withStatus(status(3, "c"), now))
        assertEquals(Page.A, pages.targetPage)
        assertEquals("見送り: 暗闇 (5.0 lx, 0 秒)", AutoSwitchDiagnostics.line)
    }

    // --- 接続テスト ---

    private fun bridgeScreen(): Screen {
        val s = open()
        s.fillValid()
        s.set("bridgeUrl", server.url("/").toString())
        return s
    }

    @Test fun connectionTestSucceedsAndSendsOnlyTheBridgeToken() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"asr":true,"tts":true,"daidai":true}"""))
        val s = bridgeScreen()
        try {
            s.click("ブリッジの接続テスト")
            assertEquals("通信は別スレッド: 押した時点では待機表示", "接続中…", s.text("bridgeResult"))
            s.waitFor("bridgeResult")
            assertTrue(s.text("bridgeResult"), s.text("bridgeResult").startsWith("OK"))
            val req = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/health", req.path)
            assertEquals("Bearer $bridgeToken", req.getHeader("Authorization"))
            assertNotEquals("Bearer $panelToken", req.getHeader("Authorization"))
            assertFalse("panel のトークンはどのヘッダにも載らない", req.headers.toString().contains(panelToken))
            assertEquals(1, server.requestCount)
            assertFalse("トークンを画面に出さない", s.text("bridgeResult").contains(bridgeToken))
        } finally { s.finish() }
    }

    @Test fun connectionTestShowsTheClassificationOnly() {
        val cases = listOf(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"detail-xyz"}}""") to "認証",
            MockResponse().setResponseCode(503).setBody("""{"error":{"code":"asr_unavailable","message":"detail-xyz"}}""") to "HTTP 503",
            MockResponse().setBody("not json at all") to "応答が正しくない",
            MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:9/") to "HTTP 302",
        )
        for ((response, snippet) in cases) {
            server.enqueue(response)
            val s = bridgeScreen()
            try {
                s.click("ブリッジの接続テスト")
                s.waitFor("bridgeResult")
                assertTrue("${s.text("bridgeResult")} / $snippet", s.text("bridgeResult").startsWith("NG") && s.text("bridgeResult").contains(snippet))
                assertFalse(s.text("bridgeResult").contains("detail-xyz"))
                assertFalse(s.text("bridgeResult").contains("not json at all"))
            } finally { s.finish() }
        }
    }

    @Test fun connectionTestReportsUnreachable() {
        val s = bridgeScreen()
        val url = server.url("/").toString()
        server.shutdown()
        try {
            s.set("bridgeUrl", url)
            s.click("ブリッジの接続テスト")
            s.waitFor("bridgeResult")
            assertTrue(s.text("bridgeResult"), s.text("bridgeResult").contains("接続できない"))
        } finally { s.finish() }
    }

    @Test fun connectionTestRefusesBadInputWithoutAnyRequest() {
        val cases = listOf(
            Triple("bridgeUrl", "", "URL を入れる"),
            Triple("bridgeUrl", "http://user:pw@127.0.0.1:1", "ユーザー情報"),
            Triple("bridgeToken", "", "トークンを入れる"),
            Triple("bridgeToken", "short", "16 文字以上"),
            Triple("bridgeToken", panelToken, "別の値"),
        )
        for ((field, value, snippet) in cases) {
            val s = bridgeScreen()
            try {
                s.set(field, value)
                s.click("ブリッジの接続テスト")
                assertTrue("${s.text("bridgeResult")} / $snippet", s.text("bridgeResult").contains(snippet))
                assertFalse(s.text("bridgeResult").contains(panelToken))
            } finally { s.finish() }
        }
        assertEquals("panel のトークンと同じ値は、送る前に止める", 0, server.requestCount)
    }

    // --- 診断の 1 行 ---

    @Test fun diagnosticsLineShowsTheLatestJudgement() {
        val s = open()
        try {
            assertEquals("直近の自動切替の判定: まだ判定なし", s.text("autoSwitchStatus"))
        } finally { s.finish() }
        AutoSwitchDiagnostics.record(AutoSwitchResult(
            AutoSwitchAction.SKIP, AutoSwitchReason.GATE_ABSENT,
            GateVerdict(Presence.ABSENT, AmbientLightGate.REASON_DARK, 0.4f, 52_000)))
        val t = Screen(Robolectric.buildActivity(SettingsActivity::class.java).setup())
        try {
            assertEquals("直近の自動切替の判定: 見送り: 暗闇 (0.4 lx, 52 秒)", t.text("autoSwitchStatus"))
        } finally { t.finish() }
    }
}
