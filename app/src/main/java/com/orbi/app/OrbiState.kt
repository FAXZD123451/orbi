package com.orbi.app

import androidx.compose.ui.geometry.Offset
import com.orbi.app.face.Expression
import kotlinx.coroutines.flow.MutableStateFlow

/** Cómo se muestra Orbi sobre las demás apps. */
enum class BubbleMode { FLOATING, TOP_BAR }

/** En qué parte de una conversación está Orbi. */
enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

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
    /** Tienes los dos ojos cerrados un rato (Orbi se adormece contigo). */
    val userEyesClosed = MutableStateFlow(false)
    /** La cámara está activa pero todavía no te encuentra: Orbi te busca con la mirada. */
    val searching = MutableStateFlow(false)
    /** Algo quiere que la isla superior se expanda un momento (por ejemplo, una notificación). */
    val attention = MutableStateFlow(0L)
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

    // ---- Asistente de voz ----
    val assistant = MutableStateFlow(AssistantState.IDLE)
    /** Escuchar siempre por si dices "Oye Orbi". */
    val voiceEnabled = MutableStateFlow(true)
    val earReady = MutableStateFlow(false)      // modelo de Vosk cargado
    val brainInstalled = MutableStateFlow(false) // modelo de Gemma presente
    val lastHeard = MutableStateFlow("")
    val lastReply = MutableStateFlow("")
    /** Progreso al copiar el modelo de Gemma (0..1), o null si no se está copiando. */
    val brainCopyProgress = MutableStateFlow<Float?>(null)

    // ---- Manos y entorno ----
    val handsEnabled = MutableStateFlow(true)
    val handVisible = MutableStateFlow(false)
    /** Dónde está tu mano (-1..1): si no ve tu cara, Orbi mira tu mano. */
    val handLook = MutableStateFlow(Offset.Zero)
    /** Último gesto reconocido (para el modo creador). */
    val lastGesture = MutableStateFlow("")
    /** Usar el reconocimiento de voz de Google en línea si no hay uno dentro del celular. */
    val onlineSpeech = MutableStateFlow(true)

    // ---- Modo creador (depuración en vivo; nada de esto se guarda) ----
    val creatorMode = MutableStateFlow(false)
    val debugFrame = MutableStateFlow<com.orbi.app.vision.DebugFrame?>(null)
    /** Lo que el oído va entendiendo mientras espera "Oye Orbi" (solo con el modo creador abierto). */
    val earHearing = MutableStateFlow("")

    // ---- Preferencias y pantallas ----
    val soundEnabled = MutableStateFlow(true)
    val bubbleVisible = MutableStateFlow(false)
    val bubbleMode = MutableStateFlow(BubbleMode.FLOATING)
    /** Lado de la burbuja flotante, en dp. */
    val floatSizeDp = MutableStateFlow(96f)
    /** Alto de la barra superior, en dp (incluye la zona de la barra de estado). */
    val barHeightDp = MutableStateFlow(150f)
    val nightMode = MutableStateFlow(false)
    /** La app está abierta en primer plano: Orbi ya está ahí, la isla se esconde (solo existe un Orbi). */
    val appVisible = MutableStateFlow(false)
}
