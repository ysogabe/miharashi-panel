package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** 前面にいる間だけ購読する。停止前の購読から届く遅延イベントは新しい購読へ渡さない。 */
class LightSensorReader(context: Context, private val onLux: (Float?) -> Unit) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    val name: String? get() = sensor?.name
    private var listener: SensorEventListener? = null
    var registered = false
        private set

    fun start() {
        stop()
        val light = sensor ?: return onLux(null)
        val current = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (listener !== this) return
                val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0 }
                onLux(value)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = current
        registered = runCatching {
            manager?.registerListener(current, light, SensorManager.SENSOR_DELAY_NORMAL) == true
        }.getOrDefault(false)
        if (!registered) {
            listener = null
            onLux(null)
        }
    }

    fun stop() {
        val old = listener
        listener = null
        registered = false
        if (old != null) manager?.unregisterListener(old)
    }
}
