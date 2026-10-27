package io.github.ysogabe.miharashi.panel

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
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
}
