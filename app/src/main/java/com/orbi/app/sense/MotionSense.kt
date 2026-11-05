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
 * Lee los sensores del celular:
 *  - Acelerómetro: sacudidas (se marea), inclinación (sus ojos "caen"), boca abajo (siesta),
 *    golpecitos "toc toc" y caída libre (grita y aterriza mareado).
 *  - Sensor de luz: si apagas la luz del cuarto, le da sueño; si la prendes, se sorprende.
 */
object MotionSense : SensorEventListener {
    private const val FACE_DOWN_Z = -8.0f     // gravedad en Z con la pantalla mirando a la mesa
    private const val FACE_UP_Z = -4.0f       // margen para no parpadear entre estados
    private const val FACE_DOWN_MS = 900L
    private const val DARK_LUX = 4f
    private const val BRIGHT_LUX = 25f
    private const val DARK_MS = 4000L

    /** Pantalla mirando hacia abajo ahora mismo (sin esperar). La cámara lo usa para no confundirlo con "taparla". */
    @Volatile var pointingDown = false
        private set
    private var downSince = 0L
    private var lux = -1f
    private var darkSince = 0L

    private const val FREE_FALL = 2.5f        // m/s²: casi sin peso
    private const val FREE_FALL_MS = 120L
    private const val LANDING = 18f           // golpe al aterrizar
    private const val KNOCK_MIN = 5f          // golpecito: entre esto y JOLT
    private var fallSince = 0L
    private var falling = false
    private var landingDeadline = 0L
    private var lastKnockSpike = 0L
    private var lastKnock = 0L
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
        manager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        started = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(event)
            Sensor.TYPE_LIGHT -> onLight(event.values[0])
        }
    }

    private fun onLight(value: Float) {
        val now = SystemClock.elapsedRealtime()
        lux = if (lux < 0) value else lux * 0.7f + value * 0.3f
        val dark = OrbiState.roomDark.value
        if (!dark && lux < DARK_LUX && !pointingDown) {
            if (darkSince == 0L) darkSince = now
            if (now - darkSince > DARK_MS) {
                OrbiState.roomDark.value = true
                MoodEngine.onLightsOff()
            }
        } else if (lux >= DARK_LUX) {
            darkSince = 0L
        }
        if (dark && lux > BRIGHT_LUX) {
            OrbiState.roomDark.value = false
            MoodEngine.onLightsOn()
        }
    }

    private fun onAccel(event: SensorEvent) {
        // Filtro paso bajo: separa la gravedad (lenta) del movimiento (rápido).
        for (i in 0..2) gravity[i] = gravity[i] * 0.9f + event.values[i] * 0.1f
        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]
        val force = sqrt(lx * lx + ly * ly + lz * lz)

        val now = SystemClock.elapsedRealtime()

        // Boca abajo: la gravedad apunta "hacia la pantalla" un rato seguido.
        val z = gravity[2]
        if (z < FACE_DOWN_Z) {
            pointingDown = true
            if (downSince == 0L) downSince = now
            if (!OrbiState.faceDown.value && now - downSince > FACE_DOWN_MS) {
                OrbiState.faceDown.value = true
                MoodEngine.onFaceDown()
            }
        } else if (z > FACE_UP_Z) {
            pointingDown = false
            downSince = 0L
            if (OrbiState.faceDown.value) {
                OrbiState.faceDown.value = false
                MoodEngine.onPickedUp()
            }
        }

        // Caída libre: en el aire el acelerómetro marca casi 0 (no hay nada que lo sostenga).
        val total = sqrt(event.values[0].let { it * it } + event.values[1].let { it * it } + event.values[2].let { it * it })
        if (total < FREE_FALL) {
            if (fallSince == 0L) fallSince = now
            if (!falling && now - fallSince > FREE_FALL_MS) {
                falling = true
                landingDeadline = now + 3000
                MoodEngine.onFreeFall()
            }
        } else {
            fallSince = 0L
            if (falling && (total > LANDING || now > landingDeadline)) {
                falling = false
                MoodEngine.onLanded()
            }
        }

        // "Toc, toc": dos golpecitos secos (más suaves que una sacudida) separados 0,1-0,6 s.
        if (!falling && force in KNOCK_MIN..JOLT && now - lastKnockSpike > 80) {
            if (now - lastKnockSpike in 100..600 && now - lastKnock > 2000 && now - lastShake > 1500) {
                lastKnock = now
                MoodEngine.onKnock()
            }
            lastKnockSpike = now
        }

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
