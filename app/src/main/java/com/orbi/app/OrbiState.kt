package com.orbi.app

import androidx.compose.ui.geometry.Offset
import com.orbi.app.face.Expression
import kotlinx.coroutines.flow.MutableStateFlow

/** Cómo se muestra Orbi sobre las demás apps. */
enum class BubbleMode { FLOATING, TOP_BAR }

/** Movimientos de cabeza de Orbi que se disparan una vez (asentir, negar). */
enum class CueType { NOD, HEAD_SHAKE }

data class Cue(val type: CueType, val id: Long)

/**
 * Estado compartido entre la app, la burbuja, el modo noche, los sensores y el cerebro.
 * Nadie elige la expresión a mano: la escribe [com.orbi.app.brain.MoodEngine].
 */
object OrbiState {
    /** Lo que Orbi siente ahora. */
    val expression = MutableStateFlow(Expression.NEUTRAL)
    /** Ojo que cierra al guiñar (-1 izquierdo, 1 derecho). */
    val winkSide = MutableStateFlow(1)
    /** Último movimiento de cabeza pedido (asiente o niega). */
    val cue = MutableStateFlow<Cue?>(null)

    // ---- Cámara ----
    /** Hacia dónde está tu cara (-1..1). */
    val look = MutableStateFlow(Offset.Zero)
    val faceVisible = MutableStateFlow(false)
    val smiling = MutableStateFlow(false)
    /** Inclinación de tu cabeza en grados (Orbi la imita). */
    val headTilt = MutableStateFlow(0f)
    /** Tienes la boca abierta (Orbi se sorprende contigo). */
    val mouthOpen = MutableStateFlow(false)
    val trackingEnabled = MutableStateFlow(true)
    val cameraAllowed = MutableStateFlow(false)

    // ---- Sensores y avisos ----
    /** Hacia dónde "caen" sus ojos cuando inclinas el celular. */
    val gravity = MutableStateFlow(Offset.Zero)
    /** Mirada forzada un momento (por ejemplo, hacia una notificación nueva). */
    val lookHint = MutableStateFlow<Offset?>(null)
    val batteryLow = MutableStateFlow(false)
    val notificationsAllowed = MutableStateFlow(false)
    /** El celular está boca abajo sobre la mesa. */
    val faceDown = MutableStateFlow(false)
    /** El cuarto está a oscuras (sensor de luz). */
    val roomDark = MutableStateFlow(false)

    // ---- Preferencias y pantallas ----
    val soundEnabled = MutableStateFlow(true)
    val bubbleVisible = MutableStateFlow(false)
    val bubbleMode = MutableStateFlow(BubbleMode.FLOATING)
    /** Lado de la burbuja flotante, en dp. */
    val floatSizeDp = MutableStateFlow(96f)
    /** Alto de la barra superior, en dp (incluye la zona de la barra de estado). */
    val barHeightDp = MutableStateFlow(150f)
    val nightMode = MutableStateFlow(false)
}
