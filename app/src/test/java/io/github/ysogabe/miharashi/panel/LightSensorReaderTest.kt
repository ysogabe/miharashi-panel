package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LightSensorReaderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun manager() = shadowOf(context.getSystemService(SensorManager::class.java))
    private fun sensor() = ShadowSensor.newInstance(Sensor.TYPE_LIGHT).also { manager().addSensor(it) }
    private fun event(sensor: Sensor, value: Float): SensorEvent = SensorEventBuilder.newBuilder()
        .setSensor(sensor).setValues(floatArrayOf(value)).build()

    @Test fun missingOrUnregisterableSensorReportsUnavailableWithoutListening() {
        val readings = mutableListOf<Float?>()
        val absent = LightSensorReader(context) { readings += it }
        absent.start()
        assertNull(absent.name)
        assertFalse(absent.registered)
        assertEquals(listOf<Float?>(null), readings)
        sensor()
        manager().setForceListenersToFail(true)
        val failed = LightSensorReader(context) { readings += it }
        failed.start()
        assertFalse(failed.registered)
        assertEquals(listOf<Float?>(null, null), readings)
        failed.stop()
    }

    @Test fun restartRejectsEventsFromPreviousSubscriptionAndStopUnregisters() {
        val sensor = sensor()
        val readings = mutableListOf<Float?>()
        val reader = LightSensorReader(context) { readings += it }
        reader.start()
        assertTrue(reader.registered)
        val oldListener = manager().listeners.single()
        oldListener.onSensorChanged(event(sensor, 31f))
        assertEquals(listOf(31f), readings)
        reader.stop()
        assertTrue(manager().listeners.isEmpty())
        oldListener.onSensorChanged(event(sensor, 0f))
        assertEquals(listOf(31f), readings)
        reader.start()
        oldListener.onSensorChanged(event(sensor, 999f))
        manager().sendSensorEventToListeners(event(sensor, Float.NaN), sensor)
        manager().sendSensorEventToListeners(event(sensor, 4f), sensor)
        assertEquals(listOf(31f, null, 4f), readings)
        reader.stop()
        assertTrue(manager().listeners.isEmpty())
    }

    @Test fun activityAppliesAmbientBrightnessAndRestoresSystemBrightnessWhenPaused() {
        val sensor = sensor()
        // Robolectric 4.14 は未ロック時に任意のnullコールバックを呼ぶため、スワイプロック状態で起動。
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(PanelActivity::class.java).setup()
        val activity = controller.get()
        try {
            manager().sendSensorEventToListeners(event(sensor, 500f), sensor)
            assertEquals(1f, activity.window.attributes.screenBrightness)
            manager().sendSensorEventToListeners(event(sensor, 0f), sensor)
            assertEquals(0.05f, activity.window.attributes.screenBrightness)
            manager().sendSensorEventToListeners(event(sensor, Float.NaN), sensor)
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            controller.pause()
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            assertTrue(manager().listeners.isEmpty())
            controller.resume()
            assertTrue(manager().listeners.isNotEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun settingsSavePreservesOtherValuesAndRejectsPartialInvalidUpdate() {
        val settings = Settings(context)
        settings.save("http://localhost", "0123456789abcdef", 8, false, false, 2.5f)
        assertFalse(settings.autoBrightness)
        assertFalse(settings.darkOff)
        assertEquals(2.5f, settings.darkLux)
        try {
            settings.save("http://localhost", "0123456789abcdef", 10, true, true, Float.NaN)
            fail("invalid threshold must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(8, settings.sleepMinutes)
        assertFalse(settings.autoBrightness)
        assertEquals(2.5f, settings.darkLux)
    }

    @Test fun savedTokenIsMaskedWhenSettingsFirstOpens() {
        val token = "test-token-0123456789"
        Settings(context).save("http://localhost", token, 3, true, true, 1f)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val field = SettingsActivity::class.java.getDeclaredField("token")
                .apply { isAccessible = true }.get(controller.get()) as EditText
            assertTrue(field.transformationMethod is PasswordTransformationMethod)
            assertNotEquals(token, field.transformationMethod.getTransformation(field.text, field).toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun queuedDarknessRequestIsCancelledWhenLightChangesOrUserTurnsMonitorOn() {
        val sensor = sensor()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val fixture = javaClass.getResource("/fixtures/status-display.json")!!.readText()
        val status = KioskStatus.parse(fixture).copy(display = DisplayInfo(true, "auto", true))
        for (change in listOf("bright", "invalid", "manual", "remote_manual", "uncontrollable", "offline", "stale")) {
            val server = MockWebServer().apply { start() }
            val controller = Robolectric.buildActivity(PanelActivity::class.java).setup()
            val activity = controller.get()
            val panel = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PanelView
            val sync = KioskSync(KioskClient(server.url("/").toString(), "test-token-0123456789"), PanelState()) {}
            // Hold the real sender queue without starting unrelated polling and SSE workers.
            KioskSync::class.java.getDeclaredField("running").apply { isAccessible = true }.set(sync, true)
            PanelActivity::class.java.getDeclaredField("sync").apply { isAccessible = true }.set(activity, sync)
            val sender = KioskSync::class.java.getDeclaredField("sender").apply { isAccessible = true }
                .get(sync) as ExecutorService
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            sender.execute { blocked.countDown(); release.await(5, TimeUnit.SECONDS) }
            try {
                assertTrue(blocked.await(2, TimeUnit.SECONDS))
                manager().sendSensorEventToListeners(event(sensor, 0f), sensor)
                shadowOf(Looper.getMainLooper()).idleFor(29, TimeUnit.SECONDS)
                panel.state = PanelState().withStatus(status, SystemClock.elapsedRealtime())
                shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
                val request = PanelActivity::class.java.getDeclaredField("automaticRequest")
                    .apply { isAccessible = true }.get(activity)
                assertNotNull("a darkness request must be waiting in the sender queue", request)
                when (change) {
                    "bright" -> manager().sendSensorEventToListeners(event(sensor, 31f), sensor)
                    "invalid" -> manager().sendSensorEventToListeners(event(sensor, Float.NaN), sensor)
                    "remote_manual", "uncontrollable", "offline" -> {
                        val current = PanelState().withStatus(status, SystemClock.elapsedRealtime())
                        val updated = when (change) {
                            "remote_manual" -> current.copy(status = status.copy(display = DisplayInfo(true, "force_on", true)))
                            "uncontrollable" -> current.copy(status = status.copy(display = DisplayInfo(true, "auto", false)))
                            else -> current.withError("offline")
                        }
                        PanelActivity::class.java.getDeclaredMethod("onKioskState", PanelState::class.java)
                            .apply { isAccessible = true }.invoke(activity, updated)
                    }
                    "stale" -> shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
                    "manual" -> {
                        server.enqueue(MockResponse().setResponseCode(204))
                        server.enqueue(MockResponse().setBody(fixture))
                        panel.configured = true
                        panel.layout(0, 0, 960, 480)
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val touch = MotionEvent.obtain(0, 0, action, 100f, 270f, 0)
                            panel.onTouchEvent(touch)
                            touch.recycle()
                        }
                    }
                }
                release.countDown()
                val drained = CountDownLatch(1)
                sender.execute { drained.countDown() }
                assertTrue(drained.await(2, TimeUnit.SECONDS))
                if (change == "manual") {
                    assertEquals("/api/v1/display", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                    // Only the user's force_on and its status refresh may reach the server.
                    assertEquals("/api/v1/status", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                    assertEquals(2, server.requestCount)
                } else {
                    assertEquals("$change must cancel the pending force_off", 0, server.requestCount)
                }
            } finally {
                release.countDown()
                controller.pause().stop().destroy()
                server.shutdown()
            }
        }
    }
}
