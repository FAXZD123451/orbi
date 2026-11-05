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
import androidx.camera.core.Preview
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
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine
import com.orbi.app.sense.MotionSense
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Mira por la cámara frontal y le cuenta a Orbi qué haces con la cara.
 *
 * Todo ocurre en el celular (ML Kit con el modelo incluido): ninguna imagen se guarda
 * ni se envía a internet. Solo salen de aquí datos como "hay cara", "dónde está" o "sonríe".
 *
 * Cada detección pasa por un filtro de tiempo ([Hold]) para que un cuadro con ruido no
 * cambie la cara de Orbi: tiene que mantenerse un poco antes de contar.
 *
 * Varias pantallas pueden querer la cámara (la burbuja, la app, el modo noche). Se apilan:
 * la cámara se vincula a la última que la pidió y vuelve a la anterior cuando esa la suelta.
 */
object FaceTracker {
    private const val TAG = "FaceTracker"
    private const val MIN_FRAME_MS = 120L     // ~8 análisis por segundo
    private const val IDLE_FRAME_MS = 500L    // modo ahorro: 2 por segundo...
    private const val IDLE_AFTER_MS = 10_000L // ...cuando lleva 10 s sin ver a nadie
    private const val FACE_ON_FRAMES = 2      // cuadros seguidos con cara para decir "te veo"
    private const val LOSE_MS = 900L          // tiempo sin cara para decir "ya no te veo"
    private const val SEARCH_MS = 12_000L     // cuánto rato te busca antes de rendirse
    private const val GAIN = 1.7f             // exagera un poco el movimiento de los ojos
    private const val SMOOTH = 0.3f           // suavizado para que los ojos no tiemblen
    private const val Y_BIAS = 0.25f          // calibración vertical (ver onFaces)
    private const val DARK_LEVEL = 22         // brillo por debajo del cual la cámara está tapada
    private const val MOUTH_OPEN = 0.07f      // hueco entre labios / alto de la cara
    private const val MOUTH_CLOSE = 0.05f
    private const val YAWN_MS = 1800L         // boca abierta tanto rato = bostezo
    private const val NEAR_RATIO = 0.55f      // cara más ancha que esto (respecto a la imagen) = muy cerca
    private const val STARE_MS = 6000L        // mirarlo quieto este rato = mirada fija
    private const val STARE_COOLDOWN_MS = 30_000L
    private const val HEAD_COOLDOWN_MS = 1800L

    private val owners = ArrayDeque<LifecycleOwner>()
    private val executor = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // labios: boca abierta
                .setMinFaceSize(0.12f)
                .build()
        )
    }

    // ---- Filtros de tiempo (encender / apagar) ----
    private val smile = Hold(onMs = 400, offMs = 700)
    private val mouth = Hold(onMs = 250, offMs = 350)
    private val eyesClosed = Hold(onMs = 1500, offMs = 400)
    private val near = Hold(onMs = 500, offMs = 800)

    @Volatile private var lastFrame = 0L
    @Volatile private var lastFaceAt = 0L
    @Volatile private var covered = false
    private var faceFrames = 0
    private var smoothX = 0f
    private var smoothY = 0f
    private var smoothTilt = 0f
    private var searchSince = 0L
    private var mouthOpenSince = 0L
    private var yawnDone = false
    private var stareAnchor = Offset.Zero
    private var stareSince = 0L
    private var lastStare = 0L
    private var winkCandidate = 0
    private var winkFrames = 0
    private var lastWink = 0L
    private var lastHeadGesture = 0L
    private val nod = Swing(amplitude = 9f)
    private val yawSwing = Swing(amplitude = 14f)
    private var lastLog = 0L

    // ---- Modo creador ----
    /** Si se pone, la cámara también muestra su imagen en esa vista (solo en el modo creador). */
    var previewProvider: Preview.SurfaceProvider? = null
    @Volatile private var frameStart = 0L
    private var fps = 0f
    private var lastFrameDone = 0L

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
                lose(SystemClock.elapsedRealtime(), search = false)
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
                val preview = previewProvider?.let { surface ->
                    Preview.Builder().build().also { it.surfaceProvider = surface }
                }
                if (preview != null) {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } else {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                }
                // La cámara acaba de abrirse: si no te ve todavía, empieza a buscarte.
                if (!OrbiState.faceVisible.value) startSearching(SystemClock.elapsedRealtime())
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir la cámara frontal", e)
                lose(SystemClock.elapsedRealtime(), search = false)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        val interval = if (now - lastFaceAt < IDLE_AFTER_MS || covered) MIN_FRAME_MS else IDLE_FRAME_MS
        if (media == null || now - lastFrame < interval) {
            proxy.close()
            return
        }
        lastFrame = now
        frameStart = now
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

    private fun onFaces(faces: List<Face>, w: Float, h: Float, light: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameDone > 0) fps = fps * 0.8f + (1000f / (now - lastFrameDone).coerceAtLeast(1)) * 0.2f
        lastFrameDone = now
        if (OrbiState.creatorMode.value) publishDebug(faces, w, h, light, now)
        watchCover(now, light)

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            faceFrames = 0
            if (OrbiState.faceVisible.value && now - lastFaceAt > LOSE_MS) lose(now, search = true)
            // Si lleva un buen rato buscando, se rinde (y vuelve a mirar de reojo, tranquilo).
            if (OrbiState.searching.value && now - searchSince > SEARCH_MS) OrbiState.searching.value = false
            return
        }
        lastFaceAt = now
        // Hacen falta 2 cuadros seguidos con cara para dar por hecho que estás ahí.
        if (++faceFrames < FACE_ON_FRAMES && !OrbiState.faceVisible.value) return

        // ---- Hacia dónde mirar ----
        // La cámara frontal ve la escena "de frente": tu derecha queda a la izquierda de la imagen.
        val nx = face.boundingBox.exactCenterX() / w * 2 - 1
        val ny = face.boundingBox.exactCenterY() / h * 2 - 1
        val targetX = (-nx * GAIN).coerceIn(-1f, 1f)
        // Como sueles tener el celular por debajo de la cara, se resta ese sesgo vertical.
        val targetY = ((ny - Y_BIAS) * GAIN).coerceIn(-1f, 1f)
        smoothX += (targetX - smoothX) * SMOOTH
        smoothY += (targetY - smoothY) * SMOOTH

        val roll = face.headEulerAngleZ
        val tiltTarget = if (abs(roll) > 7f) roll.coerceIn(-25f, 25f) else 0f
        smoothTilt += (tiltTarget - smoothTilt) * 0.3f

        OrbiState.look.value = Offset(smoothX, smoothY * 0.7f)
        OrbiState.headTilt.value = smoothTilt
        OrbiState.faceVisible.value = true
        OrbiState.searching.value = false

        // ---- Expresiones, siempre filtradas por tiempo ----
        val smileProb = face.smilingProbability ?: 0f
        // Histéresis: cuesta más empezar a contar como sonrisa (0,7) que dejar de contar (0,35).
        OrbiState.smiling.value = smile.update(if (smile.value) smileProb > 0.35f else smileProb > 0.7f, now)

        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        if (left != null && right != null) {
            OrbiState.userEyesClosed.value = eyesClosed.update(left < 0.2f && right < 0.2f, now)
            watchWink(left, right, now)
        }

        watchMouth(face, now)
        watchNear(face, w, now)
        watchStare(now)
        watchHead(face, now)

        if (now - lastLog > 1000) {
            lastLog = now
            Log.d(TAG, "cara x=%.2f y=%.2f sonrisa=%.2f(%s) ojos=%.2f/%.2f boca=%s cerca=%s luz=%d".format(
                nx, ny, smileProb, smile.value, left ?: -1f, right ?: -1f, mouth.value, near.value, light))
        }
    }

    /** Modo creador: publica todo lo calculado en este cuadro para dibujarlo encima de la cámara. */
    private fun publishDebug(faces: List<Face>, w: Float, h: Float, light: Int, now: Long) {
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        fun pts(type: Int) = face?.getContour(type)?.points.orEmpty()
        val upper = pts(FaceContour.UPPER_LIP_BOTTOM)
        val lower = pts(FaceContour.LOWER_LIP_TOP)
        val mouthRatio = if (face != null && upper.isNotEmpty() && lower.isNotEmpty()) {
            (lower[lower.size / 2].y - upper[upper.size / 2].y) / face.boundingBox.height()
        } else null
        OrbiState.debugFrame.value = DebugFrame(
            imageW = w, imageH = h, faces = faces.size,
            box = face?.boundingBox?.let { android.graphics.RectF(it) },
            faceOval = pts(FaceContour.FACE),
            lips = listOf(
                pts(FaceContour.UPPER_LIP_TOP), upper, lower, pts(FaceContour.LOWER_LIP_BOTTOM),
            ).filter { it.isNotEmpty() },
            eyes = listOf(pts(FaceContour.LEFT_EYE), pts(FaceContour.RIGHT_EYE)).filter { it.isNotEmpty() },
            smile = face?.smilingProbability,
            leftEye = face?.leftEyeOpenProbability,
            rightEye = face?.rightEyeOpenProbability,
            headX = face?.headEulerAngleX ?: 0f,
            headY = face?.headEulerAngleY ?: 0f,
            headZ = face?.headEulerAngleZ ?: 0f,
            mouthRatio = mouthRatio,
            widthRatio = face?.let { it.boundingBox.width() / w },
            light = light,
            fps = fps,
            analysisMs = now - frameStart,
        )
    }

    // ---------- Detecciones ----------

    /** Escondite: si la imagen se pone negra justo después de verte, es que tapaste la cámara. */
    private fun watchCover(now: Long, light: Int) {
        val dark = light < DARK_LEVEL
        // Boca abajo, la imagen negra es por la mesa, no por tu mano.
        if (!covered && dark && now - lastFaceAt < 4000 && !OrbiState.nightMode.value && !MotionSense.pointingDown) {
            covered = true
            MoodEngine.onCovered()
        } else if (covered && light > DARK_LEVEL + 15) {
            covered = false
            MoodEngine.onUncovered()
        }
    }

    /** Guiño: un ojo cerrado y el otro abierto 2 cuadros seguidos (un parpadeo cierra ambos). */
    private fun watchWink(left: Float, right: Float, now: Long) {
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

    /** Boca abierta (con su filtro) y bostezo: boca abierta más de [YAWN_MS]. */
    private fun watchMouth(face: Face, now: Long) {
        val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
        if (upper.isNullOrEmpty() || lower.isNullOrEmpty()) return
        val ratio = (lower[lower.size / 2].y - upper[upper.size / 2].y) / face.boundingBox.height()
        val open = mouth.update(if (mouth.value) ratio > MOUTH_CLOSE else ratio > MOUTH_OPEN, now)
        OrbiState.mouthOpen.value = open
        if (open) {
            if (mouthOpenSince == 0L) mouthOpenSince = now
            if (!yawnDone && now - mouthOpenSince > YAWN_MS) {
                yawnDone = true
                MoodEngine.onYawnSeen() // los bostezos se contagian
            }
        } else {
            mouthOpenSince = 0L
            yawnDone = false
        }
    }

    /** Te acercaste mucho a la pantalla. */
    private fun watchNear(face: Face, w: Float, now: Long) {
        val wasNear = near.value
        val isNear = near.update(face.boundingBox.width() / w > NEAR_RATIO, now)
        if (isNear && !wasNear) MoodEngine.onTooClose()
    }

    /** Mirada fija: tu cara casi no se mueve durante [STARE_MS]. */
    private fun watchStare(now: Long) {
        val current = Offset(smoothX, smoothY)
        if (stareSince == 0L || hypot(current.x - stareAnchor.x, current.y - stareAnchor.y) > 0.12f) {
            stareAnchor = current
            stareSince = now
            return
        }
        if (now - stareSince > STARE_MS && now - lastStare > STARE_COOLDOWN_MS && !OrbiState.userEyesClosed.value) {
            lastStare = now
            stareSince = now
            MoodEngine.onStare()
        }
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
        private val reversals = ArrayDeque<Long>()
        private var extreme = Float.NaN
        private var direction = 0

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

        fun range(): Float =
            if (samples.isEmpty()) 0f else samples.maxOf { it.second } - samples.minOf { it.second }

        fun reset() { samples.clear(); reversals.clear(); extreme = Float.NaN; direction = 0 }
    }

    // ---------- Perder la cara / buscarla ----------

    private fun startSearching(now: Long) {
        searchSince = now
        OrbiState.searching.value = true
    }

    private fun lose(now: Long, search: Boolean) {
        if (OrbiState.faceVisible.value) Log.d(TAG, "ya no veo la cara")
        faceFrames = 0
        smoothX = 0f
        smoothY = 0f
        smoothTilt = 0f
        stareSince = 0L
        mouthOpenSince = 0L
        smile.reset(); mouth.reset(); eyesClosed.reset(); near.reset()
        nod.reset(); yawSwing.reset()
        OrbiState.headTilt.value = 0f
        OrbiState.mouthOpen.value = false
        OrbiState.userEyesClosed.value = false
        OrbiState.faceVisible.value = false
        OrbiState.smiling.value = false
        OrbiState.look.value = Offset.Zero
        if (search) startSearching(now) else OrbiState.searching.value = false
    }
}
