package com.orbi.app.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.orbi.app.sense.MotionSense
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine
import java.util.concurrent.Executors

/**
 * Mira por la cámara frontal y le dice a Orbi dónde está tu cara y si sonríes.
 *
 * Todo ocurre en el celular (ML Kit con el modelo incluido): ninguna imagen se guarda
 * ni se envía a internet. Solo salen de aquí tres datos: si hay cara, dónde está y si sonríe.
 *
 * Varias pantallas pueden querer la cámara (la burbuja, la app, el modo noche). Se apilan:
 * la cámara se vincula a la última que la pidió y vuelve a la anterior cuando esa la suelta.
 */
object FaceTracker {
    private const val TAG = "FaceTracker"
    private const val MIN_FRAME_MS = 120L     // ~8 análisis por segundo: suficiente y ahorra batería
    private const val MISSES_TO_LOSE = 5      // fotogramas sin cara antes de decir "ya no te veo"
    private const val GAIN = 1.7f             // exagera un poco el movimiento de los ojos
    private const val SMOOTH = 0.35f          // suavizado para que los ojos no tiemblen
    private const val Y_BIAS = 0.25f          // calibración vertical (ver onFaces)
    private const val DARK_LEVEL = 22         // brillo por debajo del cual la cámara está tapada
    private const val IDLE_FRAME_MS = 500L    // modo ahorro: 2 análisis por segundo
    private const val IDLE_AFTER_MS = 10_000L // ...cuando lleva 10 s sin ver a nadie
    private const val MOUTH_OPEN = 0.07f      // hueco entre labios / alto de la cara para "boca abierta"
    private const val MOUTH_CLOSE = 0.05f     // y para volver a "cerrada" (histéresis)
    private const val HEAD_COOLDOWN_MS = 1800L

    private val owners = ArrayDeque<LifecycleOwner>()
    private val executor = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                // Contornos de los labios: para saber si abres la boca.
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.12f)
                .build()
        )
    }

    @Volatile private var lastFrame = 0L
    private var misses = 0
    private var smoothX = 0f
    private var smoothY = 0f

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun acquire(context: Context, owner: LifecycleOwner) {
        owners.remove(owner)
        owners.addLast(owner)
        rebind(context)
    }

    fun release(context: Context, owner: LifecycleOwner) {
        owners.remove(owner)
        rebind(context)
    }

    /** Vuelve a evaluar (por ejemplo, tras dar el permiso o activar/desactivar el seguimiento). */
    fun rebind(context: Context) {
        val app = context.applicationContext
        OrbiState.cameraAllowed.value = hasPermission(app)
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            owners.removeAll { it.lifecycle.currentState == Lifecycle.State.DESTROYED }
            val owner = owners.lastOrNull()
            if (owner == null || !OrbiState.trackingEnabled.value || !hasPermission(app)) {
                lose()
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            try {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir la cámara frontal", e)
                lose()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        // Modo ahorro: si hace rato que no hay nadie, mira menos veces por segundo.
        val interval = if (now - lastFaceAt < IDLE_AFTER_MS || covered) MIN_FRAME_MS else IDLE_FRAME_MS
        if (media == null || now - lastFrame < interval) {
            proxy.close()
            return
        }
        lastFrame = now
        val rotation = proxy.imageInfo.rotationDegrees
        val (w, h) = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
        val light = luminance(proxy)
        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { faces -> onFaces(faces, w.toFloat(), h.toFloat(), light) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Brillo medio de la imagen (0-255), muestreando 1 de cada 32 píxeles del canal de luz. */
    private fun luminance(proxy: ImageProxy): Int {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        var sum = 0L
        var count = 0
        var y = 0
        while (y < proxy.height) {
            var x = 0
            while (x < proxy.width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xFF
                    count++
                }
                x += 32
            }
            y += 32
        }
        return if (count == 0) 255 else (sum / count).toInt()
    }

    /** Escondite: si la imagen se pone negra justo después de verte, es que tapaste la cámara. */
    private fun watchCover(now: Long, light: Int) {
        val dark = light < DARK_LEVEL
        // Si el celular está boca abajo, la imagen negra es por la mesa, no por tu mano.
        if (!covered && dark && now - lastFaceAt < 4000 && !OrbiState.nightMode.value && !MotionSense.pointingDown) {
            covered = true
            MoodEngine.onCovered()
        } else if (covered && light > DARK_LEVEL + 15) {
            covered = false
            MoodEngine.onUncovered()
        }
    }

    /** Guiño: un ojo cerrado y el otro abierto durante 2 cuadros seguidos (un parpadeo cierra ambos). */
    private fun watchWink(face: Face, now: Long) {
        val left = face.leftEyeOpenProbability ?: return
        val right = face.rightEyeOpenProbability ?: return
        // "left" es tu ojo izquierdo; Orbi lo imita como un espejo, con su ojo del mismo lado de la pantalla.
        val side = when {
            left < 0.25f && right > 0.7f -> -1
            right < 0.25f && left > 0.7f -> 1
            else -> 0
        }
        winkFrames = if (side != 0 && side == winkCandidate) winkFrames + 1 else if (side != 0) 1 else 0
        winkCandidate = side
        if (winkFrames == 2 && now - lastWink > 1500) {
            lastWink = now
            MoodEngine.onWink(side)
        }
    }

    /**
     * Boca abierta: distancia entre el borde de abajo del labio superior y el de arriba del
     * labio inferior, comparada con el alto de la cara (así no depende de lo cerca que estés).
     */
    private fun watchMouth(face: Face) {
        val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
        if (upper.isNullOrEmpty() || lower.isNullOrEmpty()) return
        val gap = lower[lower.size / 2].y - upper[upper.size / 2].y
        val ratio = gap / face.boundingBox.height()
        val open = if (OrbiState.mouthOpen.value) ratio > MOUTH_CLOSE else ratio > MOUTH_OPEN
        mouthFrames = if (open) mouthFrames + 1 else 0
        OrbiState.mouthOpen.value = mouthFrames >= 2
    }

    /**
     * Sí / no con la cabeza: cuenta "idas y vueltas" del ángulo de la cabeza.
     * Asentir mueve el ángulo X (arriba-abajo); negar, el ángulo Y (izquierda-derecha).
     */
    private fun watchHead(face: Face, now: Long) {
        if (now - lastHeadGesture < HEAD_COOLDOWN_MS) return
        if (nod.feed(face.headEulerAngleX, now) && yawSwing.range() < 10f) {
            lastHeadGesture = now
            nod.reset(); yawSwing.reset()
            MoodEngine.onNod()
            return
        }
        if (yawSwing.feed(face.headEulerAngleY, now) && nod.range() < 10f) {
            lastHeadGesture = now
            nod.reset(); yawSwing.reset()
            MoodEngine.onHeadShake()
        }
    }

    /** Detecta un vaivén: el valor cambia de dirección [needed] veces, cada vez más de [amplitude] grados. */
    private class Swing(val amplitude: Float, val needed: Int = 3, val windowMs: Long = 1600) {
        private val samples = ArrayDeque<Pair<Long, Float>>()
        private var extreme = Float.NaN
        private var direction = 0
        private var reversals = ArrayDeque<Long>()

        fun feed(value: Float, now: Long): Boolean {
            samples.addLast(now to value)
            while (samples.isNotEmpty() && samples.first().first < now - windowMs) samples.removeFirst()
            while (reversals.isNotEmpty() && reversals.first() < now - windowMs) reversals.removeFirst()
            if (extreme.isNaN()) { extreme = value; return false }
            val delta = value - extreme
            when {
                direction >= 0 && value > extreme -> extreme = value
                direction <= 0 && value < extreme -> extreme = value
            }
            if (direction >= 0 && delta < -amplitude) { direction = -1; extreme = value; reversals.addLast(now) }
            else if (direction <= 0 && delta > amplitude) { direction = 1; extreme = value; reversals.addLast(now) }
            return reversals.size >= needed
        }

        fun range(): Float {
            if (samples.isEmpty()) return 0f
            return samples.maxOf { it.second } - samples.minOf { it.second }
        }

        fun reset() { samples.clear(); reversals.clear(); extreme = Float.NaN; direction = 0 }
    }

    private fun onFaces(faces: List<Face>, w: Float, h: Float, light: Int) {
        frames++
        val now = SystemClock.elapsedRealtime()
        watchCover(now, light)
        if (now - lastFpsLog > 5000) {
            Log.d(TAG, "analizando: $frames cuadros en 5 s, ${faces.size} cara(s) ahora")
            frames = 0
            lastFpsLog = now
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            if (++misses >= MISSES_TO_LOSE) lose()
            return
        }
        misses = 0
        lastFaceAt = now
        // La cámara frontal ve la escena "de frente": tu derecha queda a la izquierda de la imagen.
        // Por eso se invierte X, para que Orbi mire hacia donde realmente estás.
        val nx = face.boundingBox.exactCenterX() / w * 2 - 1
        val ny = face.boundingBox.exactCenterY() / h * 2 - 1
        val targetX = (-nx * GAIN).coerceIn(-1f, 1f)
        // Como sueles tener el celular por debajo de la cara, tu cara sale algo abajo en la imagen:
        // se resta ese sesgo para que en tu postura normal Orbi te mire de frente.
        val targetY = ((ny - Y_BIAS) * GAIN).coerceIn(-1f, 1f)
        smoothX += (targetX - smoothX) * SMOOTH
        smoothY += (targetY - smoothY) * SMOOTH

        // Inclinación de tu cabeza (rotación en Z). Se ignoran las pequeñas para que no tiemble.
        val roll = face.headEulerAngleZ
        val tiltTarget = if (kotlin.math.abs(roll) > 7f) roll.coerceIn(-25f, 25f) else 0f
        smoothTilt += (tiltTarget - smoothTilt) * 0.3f

        OrbiState.look.value = Offset(smoothX, smoothY * 0.7f)
        OrbiState.headTilt.value = smoothTilt
        OrbiState.faceVisible.value = true
        OrbiState.smiling.value = (face.smilingProbability ?: 0f) > 0.6f
        watchWink(face, now)
        watchMouth(face)
        watchHead(face, now)

        if (now - lastLog > 1000) {
            lastLog = now
            Log.d(TAG, "cara x=%.2f y=%.2f sonrisa=%.2f ojos=%.2f/%.2f cabeza X=%.0f Y=%.0f Z=%.0f boca=%s luz=%d".format(
                nx, ny, face.smilingProbability ?: -1f, face.leftEyeOpenProbability ?: -1f,
                face.rightEyeOpenProbability ?: -1f, face.headEulerAngleX, face.headEulerAngleY, roll,
                OrbiState.mouthOpen.value, light))
        }
    }

    private var lastLog = 0L
    private var lastFpsLog = 0L
    private var frames = 0
    private var lastFaceAt = 0L
    private var covered = false
    private var smoothTilt = 0f
    private var winkCandidate = 0
    private var winkFrames = 0
    private var lastWink = 0L
    private var mouthFrames = 0
    private var lastHeadGesture = 0L
    private val nod = Swing(amplitude = 9f)
    private val yawSwing = Swing(amplitude = 14f)

    private fun lose() {
        if (OrbiState.faceVisible.value) Log.d(TAG, "ya no veo la cara")
        misses = 0
        smoothX = 0f
        smoothY = 0f
        smoothTilt = 0f
        mouthFrames = 0
        nod.reset()
        yawSwing.reset()
        OrbiState.headTilt.value = 0f
        OrbiState.mouthOpen.value = false
        OrbiState.faceVisible.value = false
        OrbiState.smiling.value = false
        OrbiState.look.value = Offset.Zero
    }
}
