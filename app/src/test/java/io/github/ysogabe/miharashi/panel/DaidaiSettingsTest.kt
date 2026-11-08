package io.github.ysogabe.miharashi.panel

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** だいだい連携の設定キー・検証(設計書 §5.1、AC11 / AC20 の設定側)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DaidaiSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("panel", Context.MODE_PRIVATE)
    private val panelToken = "panel-token-0123456789"
    private val bridgeToken = "bridge-token-abcdefghij"

    private fun base(daidai: DaidaiSettings = DaidaiSettings()) =
        PanelSettings("http://localhost:8790", panelToken, 3, true, true, 1f, daidai)

    private fun assertRejected(s: PanelSettings, before: PanelSettings) {
        val settings = Settings(context)
        try {
            settings.save(s)
            fail("rejected: $s")
        } catch (e: IllegalArgumentException) {
            assertFalse("メッセージにトークンを含めない", e.message.orEmpty().contains(s.token))
            assertFalse(e.message.orEmpty().contains(s.daidai.token) && s.daidai.token.isNotEmpty())
        }
        assertEquals("保存されていない", before, settings.load())
    }

    // ---- 既定値・保存・既存キー ----

    @Test
    fun defaultsWhenNothingIsSaved() {
        val d = Settings(context).daidai
        assertEquals(DaidaiSettings(), d)
        assertEquals("", d.url)
        assertEquals("", d.token)
        assertTrue(d.autoSwitch)
        assertEquals(1f, d.gateDarkLux)
        assertEquals(30, d.gateDarkSeconds)
        assertTrue(d.autoListen)
        assertEquals(60, d.idleSeconds)
    }

    @Test
    fun saveAndLoadRoundTripUsesDocumentedKeys() {
        val d = DaidaiSettings("http://192.168.0.132:8800/bridge/", bridgeToken, false, 2.5f, 45, false, 120)
        val s = base(d)
        val settings = Settings(context)
        settings.save(s)
        assertEquals(s.copy(daidai = d.copy(url = "http://192.168.0.132:8800/bridge")), settings.load())
        assertEquals("http://192.168.0.132:8800/bridge", prefs.getString("daidai_url", null))
        assertEquals(bridgeToken, prefs.getString("daidai_token", null))
        assertFalse(prefs.getBoolean("daidai_auto_switch", true))
        assertEquals(2.5f, prefs.getFloat("daidai_gate_dark_lux", 0f))
        assertEquals(45, prefs.getInt("daidai_gate_dark_seconds", 0))
        assertFalse(prefs.getBoolean("daidai_auto_listen", true))
        assertEquals(120, prefs.getInt("daidai_idle_seconds", 0))
        // 既存キーは同じ名前のまま
        assertEquals("http://localhost:8790", prefs.getString("url", null))
        assertEquals(panelToken, prefs.getString("token", null))
        assertEquals(3, prefs.getInt("sleep_minutes", -1))
        assertTrue(prefs.getBoolean("auto_brightness", false))
        assertTrue(prefs.getBoolean("dark_off", false))
        assertEquals(1f, prefs.getFloat("dark_lux", -1f))
    }

    @Test
    fun legacySaveKeepsDaidaiSettings() {
        val settings = Settings(context)
        val d = DaidaiSettings("https://example.com", bridgeToken, false, 5f, 90, false, 30)
        settings.save(base(d))
        settings.save("http://localhost:9", "another-panel-token-12", 8, false, false, 2.5f)
        val s = settings.load()
        assertEquals(d, s.daidai)
        assertEquals("another-panel-token-12", s.token)
        assertEquals(8, s.sleepMinutes)
        assertFalse(s.autoBrightness)
        assertEquals(2.5f, s.darkLux)
    }

    @Test
    fun legacySaveRejectsPanelTokenEqualToStoredDaidaiToken() {
        val settings = Settings(context)
        settings.save(base(DaidaiSettings(token = bridgeToken)))
        val before = settings.load()
        try {
            // 保存の順序: だいだいのトークンが先に保存され、後から panel のトークンが同じ値になる
            settings.save("http://localhost:8790", bridgeToken, 3, true, true, 1f)
            fail("rejected")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains(bridgeToken))
        }
        assertEquals(before, settings.load())
    }

    @Test
    fun legacySaveStillRejectsInvalidInput() {
        val settings = Settings(context)
        settings.save("http://localhost", panelToken, 8, false, false, 2.5f)
        listOf(
            { settings.save("localhost", panelToken, 8, false, false, 2.5f) },
            { settings.save("http://localhost", "short", 8, false, false, 2.5f) },
            { settings.save("http://localhost", panelToken, 241, false, false, 2.5f) },
            { settings.save("http://localhost", panelToken, 8, false, false, Float.NaN) },
        ).forEach {
            try {
                it()
                fail("rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(8, settings.sleepMinutes)
        assertEquals(2.5f, settings.darkLux)
    }

    // ---- 範囲外の拒否 ----

    @Test
    fun outOfRangeValuesAreRejectedAndNothingIsSaved() {
        val settings = Settings(context)
        settings.save(base(DaidaiSettings(url = "http://h:1", token = bridgeToken, gateDarkSeconds = 40)))
        val before = settings.load()
        val d = before.daidai
        listOf(
            d.copy(gateDarkLux = -0.1f),
            d.copy(gateDarkLux = 100.1f),
            d.copy(gateDarkLux = Float.NaN),
            d.copy(gateDarkLux = Float.POSITIVE_INFINITY),
            d.copy(gateDarkSeconds = -1),
            d.copy(gateDarkSeconds = 601),
            d.copy(idleSeconds = 9),
            d.copy(idleSeconds = 601),
            d.copy(idleSeconds = 0),
            d.copy(idleSeconds = Int.MAX_VALUE),
        ).forEach { assertRejected(before.copy(daidai = it, sleepMinutes = 99), before) }
    }

    @Test
    fun boundaryValuesAreAccepted() {
        val settings = Settings(context)
        listOf(
            DaidaiSettings(gateDarkLux = 0f, gateDarkSeconds = 0, idleSeconds = 10),
            DaidaiSettings(gateDarkLux = 100f, gateDarkSeconds = 600, idleSeconds = 600),
        ).forEach {
            settings.save(base(it))
            assertEquals(it, settings.daidai)
        }
    }

    @Test
    fun parsersFollowRanges() {
        assertEquals(0f, Settings.parseGateDarkLux(" 0 "))
        assertEquals(100f, Settings.parseGateDarkLux("100"))
        listOf("-0.1", "100.1", "NaN", "Infinity", "", "x").forEach { assertNull(it, Settings.parseGateDarkLux(it)) }
        assertEquals(0, Settings.parseGateDarkSeconds("0"))
        assertEquals(600, Settings.parseGateDarkSeconds("600"))
        listOf("-1", "601", "1.5", "", "x").forEach { assertNull(it, Settings.parseGateDarkSeconds(it)) }
        assertEquals(10, Settings.parseIdleSeconds("10"))
        assertEquals(600, Settings.parseIdleSeconds(" 600 "))
        listOf("9", "601", "0", "", "x").forEach { assertNull(it, Settings.parseIdleSeconds(it)) }
    }

    @Test
    fun invalidStoredValuesFallBackToDefaultsPerItem() {
        // install.sh などが直接書いた不正な値。その項目だけ既定値に戻る
        prefs.edit().putString("url", "http://localhost").putString("token", panelToken)
            .putString("daidai_url", "http://u:p@host").putString("daidai_token", "short")
            .putFloat("daidai_gate_dark_lux", 500f).putInt("daidai_gate_dark_seconds", 9999)
            .putInt("daidai_idle_seconds", 1).putBoolean("daidai_auto_switch", false).apply()
        val d = Settings(context).daidai
        assertEquals("", d.url)
        assertEquals("", d.token)
        assertEquals(1f, d.gateDarkLux)
        assertEquals(30, d.gateDarkSeconds)
        assertEquals(60, d.idleSeconds)
        assertFalse("正しい項目は保たれる", d.autoSwitch)
        // 型が違う値でも落ちない
        prefs.edit().putString("daidai_idle_seconds", "x").putInt("daidai_url", 5).apply()
        assertEquals(60, Settings(context).daidai.idleSeconds)
        assertEquals("", Settings(context).daidai.url)
    }

    @Test
    fun storedDaidaiTokenEqualToPanelTokenIsTreatedAsUnset() {
        prefs.edit().putString("url", "http://localhost").putString("token", panelToken)
            .putString("daidai_token", panelToken).apply()
        assertEquals("", Settings(context).daidai.token)
    }

    // ---- daidai_url ----

    @Test
    fun urlAcceptsHttpAndHttpsWithOptionalPathPrefix() {
        listOf(
            "http://192.168.0.132:8800", "https://bridge.local", "http://host:80/", "http://host/prefix", "https://host/a/b/",
            "HTTP://Host", "  http://host:8800  ", "http://host:8800\n", "http://[::1]:8800", "http://my-host_1.example.com/x%20y",
        ).forEach { assertNull(it, Settings.validateDaidaiUrl(it)) }
        assertNull("空は未設定として許す", Settings.validateDaidaiUrl(""))
        assertNull(Settings.validateDaidaiUrl("   "))
    }

    @Test
    fun urlRejections() {
        listOf(
            "ftp://host", "ws://host", "file:///etc/passwd", "host:8800", "192.168.0.132:8800", "//host", "javascript:alert(1)",
            "http://", "https://", "http:///path", "http://:8800", "http://@",
            "http://user@host", "http://user:pass@host:8800", "http://@host", "http://host@evil.example", "http://host:8800@evil.example/",
            "http://host?x=1", "http://host/?", "http://host/p?token=abc", "http://host#frag", "http://host/#", "http://host/p#x",
            "http://ho st", "http://host/a b", "http://host\nx", "http://ho\tst", "http://host\\@evil", "http://host/\u0000",
            "http://host:99999", "http://host:0x", "http://ホスト",
            "http://host:-1", "http://host:0", "http://host:65536", "http://host:abc", "http://host:", "http://host:/path",
            "http://host:-1/p", "http://[::1]:-1", "http://[::1]:", "http://[::1]:0", "http://[::1]:65536", "http://host:+80", "http://host:000000080",
        ).forEach { assertNotNull("rejected: " + it.replace("\n", "\\n"), Settings.validateDaidaiUrl(it)) }
    }

    @Test
    fun urlPortMustBeAbsentOrWithin1To65535() {
        listOf("http://host", "http://host/", "http://host:1", "http://host:65535", "http://host:8800/p", "http://[::1]", "http://[::1]/p", "http://[::1]:65535")
            .forEach { assertNull(it, Settings.validateDaidaiUrl(it)) }
    }

    @Test
    fun urlMessagesDoNotEchoInput() {
        val secret = "http://user:s3cr3t-pass@host"
        assertFalse(Settings.validateDaidaiUrl(secret).orEmpty().contains("s3cr3t"))
    }

    @Test
    fun urlIsNormalizedOnSave() {
        val settings = Settings(context)
        listOf(
            "http://host:8800/" to "http://host:8800",
            "  http://host:8800//  " to "http://host:8800",
            "https://host/prefix/" to "https://host/prefix",
            "http://host" to "http://host",
            "" to "",
        ).forEach { (input, expected) ->
            settings.save(base(DaidaiSettings(url = input)))
            assertEquals(expected, settings.daidai.url)
        }
    }

    @Test
    fun invalidDaidaiUrlIsRejectedOnSave() {
        val settings = Settings(context)
        settings.save(base(DaidaiSettings(url = "http://good:1")))
        val before = settings.load()
        listOf("http://user@host", "http://host?x=1", "http://host#f", "ftp://host", "host").forEach {
            assertRejected(before.copy(daidai = before.daidai.copy(url = it)), before)
        }
    }

    // ---- daidai_token ----

    @Test
    fun tokenValidation() {
        assertNull("空は未設定", Settings.validateDaidaiToken("", panelToken))
        assertNull(Settings.validateDaidaiToken("   ", panelToken))
        assertNull(Settings.validateDaidaiToken("x".repeat(16), panelToken))
        assertNotNull(Settings.validateDaidaiToken("x".repeat(15), panelToken))
        assertNotNull("trim 後の長さ", Settings.validateDaidaiToken(" " + "x".repeat(15) + " ", panelToken))
        assertNotNull("panel と同じ値は拒否(AC20)", Settings.validateDaidaiToken(panelToken, panelToken))
        assertNotNull("空白の違いは同じ値", Settings.validateDaidaiToken(" $panelToken\n", panelToken))
        assertNull(Settings.validateDaidaiToken(bridgeToken, panelToken))
        assertNull("panel が未設定でも空のトークンは許す", Settings.validateDaidaiToken("", ""))
    }

    @Test
    fun tokenMessagesDoNotEchoTheValue() {
        assertFalse(Settings.validateDaidaiToken(panelToken, panelToken).orEmpty().contains(panelToken))
        assertFalse(Settings.validateDaidaiToken("short-secret", panelToken).orEmpty().contains("short-secret"))
    }

    @Test
    fun tokenEqualToPanelTokenIsRejectedOnSave() {
        val settings = Settings(context)
        settings.save(base(DaidaiSettings(token = bridgeToken)))
        val before = settings.load()
        assertRejected(before.copy(daidai = before.daidai.copy(token = panelToken)), before)
        assertRejected(before.copy(daidai = before.daidai.copy(token = "  $panelToken ")), before)
        assertRejected(before.copy(daidai = before.daidai.copy(token = "short")), before)
        // 同時に変えて同じ値になる場合も拒否
        assertRejected(before.copy(token = bridgeToken), before)
        // 空にして未設定へ戻すのは許す
        settings.save(before.copy(daidai = before.daidai.copy(token = "")))
        assertEquals("", settings.daidai.token)
    }

    @Test
    fun toStringMasksTokens() {
        val s = base(DaidaiSettings(token = bridgeToken)).toString()
        assertFalse(s.contains(panelToken))
        assertFalse(s.contains(bridgeToken))
    }
}
