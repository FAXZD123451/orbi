package com.orbi.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine

/**
 * Las manos: reconoce gestos con MediaPipe (modelo incluido en la app, funciona sin internet).
 *
 * Gestos: 👍 Thumb_Up · 👎 Thumb_Down · ✋ Open_Palm · ✊ Closed_Fist · ✌️ Victory ·
 *         ☝️ Pointing_Up · 🤟 ILoveYou
 *
 * Igual que con la cara, un gesto tiene que verse en 2 análisis seguidos para contar, y cada
 * gesto tiene una pausa antes de poder repetirse (así no "dispara" varias veces seguidas).
 */
object HandSense {
    private const val TAG = "HandSense"
    private const val MIN_SCORE = 0.65f
    private const val CONFIRM = 2
    private const val REPEAT_MS = 2500L
    private const val LOSE_MS = 700L

    private var recognizer: GestureRecognizer? = null
    private var failed = false
    private val main = Handler(Looper.getMainLooper())
    private var candidate = ""
    private var count = 0
    private val lastFired = HashMap<String, Long>()
    @Volatile private var lastHandAt = 0L

    /** Últimos puntos de las manos (21 por mano, normalizados 0..1) y gestos, para el modo creador. */
    @Volatile var lastLandmarks: List<List<PointF>> = emptyList()
        private set
    @Volatile var lastGestures: List<String> = emptyList()
        private set

    private fun ensure(context: Context): GestureRecognizer? {
        if (recognizer != null || failed) return recognizer
        recognizer = try {
            GestureRecognizer.createFromOptions(
                context,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cargar el modelo de gestos", e)
            failed = true
            null
        }
        return recognizer
    }

    /** Analiza un cuadro ya enderezado. Se llama desde el hilo de la cámara. */
    fun analyze(context: Context, bitmap: Bitmap) {
        val r = ensure(context) ?: return
        val result = try {
            r.recognize(BitmapImageBuilder(bitmap).build())
        } catch (e: Exception) {
            Log.w(TAG, "Falló el análisis de manos", e)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val hands = result.landmarks().map { hand -> hand.map { PointF(it.x(), it.y()) } }
        val gestures = result.gestures().map { list ->
            list.firstOrNull()?.takeIf { it.score() >= MIN_SCORE }?.categoryName() ?: "None"
        }
        lastLandmarks = hands
        lastGestures = gestures.zip(result.gestures().map { it.firstOrNull()?.score() ?: 0f })
            .map { (g, s) -> if (g == "None") "–" else "$g ${"%.2f".format(s)}" }

        // Mira hacia la primera mano (centro de la palma = punto 9), con el mismo espejo que la cara.
        val first = hands.firstOrNull()
        if (first != null) lastHandAt = now
        val look = first?.get(9)?.let { Offset((-(it.x * 2 - 1) * 1.6f).coerceIn(-1f, 1f), ((it.y * 2 - 1) * 1.4f).coerceIn(-1f, 1f)) }
        val best = gestures.firstOrNull { it != "None" } ?: ""

        main.post {
            if (look != null) {
                OrbiState.handLook.value = look
                OrbiState.handVisible.value = true
            } else if (SystemClock.elapsedRealtime() - lastHandAt > LOSE_MS) {
                OrbiState.handVisible.value = false
            }
            confirm(best, now)
        }
    }

    /** Un gesto cuenta si aparece 2 veces seguidas y no se repitió hace poco. */
    private fun confirm(gesture: String, now: Long) {
        if (gesture.isEmpty()) { candidate = ""; count = 0; return }
        count = if (gesture == candidate) count + 1 else 1
        candidate = gesture
        if (count == CONFIRM && now - (lastFired[gesture] ?: 0L) > REPEAT_MS) {
            lastFired[gesture] = now
            OrbiState.lastGesture.value = gesture
            Log.d(TAG, "gesto: $gesture")
            MoodEngine.onGesture(gesture)
        }
    }

    fun reset() {
        lastLandmarks = emptyList()
        lastGestures = emptyList()
        candidate = ""
        count = 0
        main.post { OrbiState.handVisible.value = false }
    }
}
