package com.orbi.app.sense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lee el acelerómetro para dos cosas:
 *  - Sacudidas: varios golpes fuertes seguidos → Orbi se marea.
 *  - Inclinación: si ladeas el celular, sus ojos "caen" hacia el lado de abajo.
 */
object MotionSense : SensorEventListener {
    private const val JOLT = 11f              // m/s² de aceleración (sin gravedad) para contar un "golpe"
    private const val JOLTS_FOR_SHAKE = 3
    private const val WINDOW_MS = 900L
    private const val COOLDOWN_MS = 1200L
    private const val TILT_DEAD_ZONE = 0.25f  // inclinaciones pequeñas se ignoran

    private val gravity = FloatArray(3)
    private val jolts = ArrayDeque<Long>()
    private var lastShake = 0L
    private var started = false

    fun start(context: Context) {
        if (started) return
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        started = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Filtro paso bajo: separa la gravedad (lenta) del movimiento (rápido).
        for (i in 0..2) gravity[i] = gravity[i] * 0.9f + event.values[i] * 0.1f
        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]
        val force = sqrt(lx * lx + ly * ly + lz * lz)

        val now = SystemClock.elapsedRealtime()
        if (force > JOLT && (jolts.isEmpty() || now - jolts.last() > 80)) {
            jolts.addLast(now)
            while (jolts.isNotEmpty() && jolts.first() < now - WINDOW_MS) jolts.removeFirst()
            if (jolts.size >= JOLTS_FOR_SHAKE && now - lastShake > COOLDOWN_MS) {
                lastShake = now
                jolts.clear()
                MoodEngine.onShake()
            }
        }

        // Si bajas el lado derecho del celular, la gravedad marca x negativa → ojos a la derecha.
        var gx = (-gravity[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        gx = if (abs(gx) < TILT_DEAD_ZONE) 0f else (gx - TILT_DEAD_ZONE * kotlin.math.sign(gx)) / (1 - TILT_DEAD_ZONE)
        val current = OrbiState.gravity.value
        if (abs(current.x - gx) > 0.04f) OrbiState.gravity.value = Offset(gx, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
