package com.orbi.app.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import com.orbi.app.AssistantState
import com.orbi.app.OrbiState
import com.orbi.app.vision.FaceTracker
import com.orbi.app.vision.SceneSense
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * El director de orquesta de la conversación:
 *
 *   "Oye Orbi" (Ear) → "¿Sí?" (Mouth) → escucha tu frase (Ear)
 *      → ¿orden rápida? (Commands)  si no → piensa (LocalBrain / Gemma)
 *      → responde hablando (Mouth) → vuelve a esperar "Oye Orbi"
 *
 * Mientras Orbi habla el micrófono está apagado, para que no se escuche a sí mismo.
 */
object Assistant {
    private val scope = MainScope()
    private lateinit var app: Context
    private var thinkJob: Job? = null
    private var hosts = 0

    fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Una pantalla (la burbuja o la app) quiere que Orbi escuche. Se lleva la cuenta para
     * apagar el micrófono solo cuando ya nadie lo necesita.
     */
    fun attach(context: Context) {
        hosts++
        setup(context)
        refresh()
    }

    fun detach() {
        hosts = (hosts - 1).coerceAtLeast(0)
        if (hosts == 0) {
            Ear.stop()
            GoogleEar.stop()
            Mouth.stop()
            set(AssistantState.IDLE)
        }
    }

    private fun setup(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Mouth.init(app)
        LocalBrain.isInstalled(app)
        Ear.onWake = ::onWake
        Ear.onCommand = ::onCommand
    }

    /** Vuelve a evaluar si hay que escuchar (tras dar el permiso, activar el interruptor...). */
    fun refresh() {
        if (!::app.isInitialized) return
        LocalBrain.isInstalled(app)
        if (!OrbiState.voiceEnabled.value || !hasMic(app) || hosts == 0) {
            android.util.Log.d("Assistant", "no escucho: voz=${OrbiState.voiceEnabled.value} mic=${hasMic(app)} pantallas=$hosts")
            if (OrbiState.assistant.value == AssistantState.IDLE) Ear.stop()
            return
        }
        if (!Ear.isInstalled(app)) {
            android.util.Log.d("Assistant", "no escucho: falta el modelo de voz en ${Ear.modelDir(app)}")
            return
        }
        Ear.load(app) { ok -> if (ok && OrbiState.assistant.value == AssistantState.IDLE) waitForWakeWord() }
    }

    private fun waitForWakeWord() {
        if (OrbiState.voiceEnabled.value && hasMic(app) && hosts > 0) Ear.listenForWake() else Ear.stop()
    }

    /** Hablar con Orbi sin decir "Oye Orbi" (por ejemplo, desde el botón de la app). */
    fun talk(context: Context) {
        setup(context)
        if (!hasMic(app) || !Ear.isInstalled(app)) return
        Ear.load(app) { ok -> if (ok) onWake("") }
    }

    private fun onWake(rest: String) {
        thinkJob?.cancel()
        OrbiState.lastHeard.value = ""
        set(AssistantState.LISTENING)
        // Lo dijo de corrido ("oye orbi, ¿qué hora es?"): se usa lo que entendió Vosk solo si
        // es una frase de verdad; si no, se le pregunta "¿Sí?" y se escucha con más precisión.
        if (rest.trim().split(" ").size >= 2) {
            onCommand(rest)
            return
        }
        val (name, text) = listOf("orbi_si" to "¿Sí?", "orbi_dime" to "¡Dime!", "orbi_aqui" to "¡Aquí estoy!").random()
        Mouth.phrase(app, name, text) { listenForCommand() }
    }

    /** Escucha tu pregunta con el mejor oído disponible: Google si se puede, si no Vosk. */
    private fun listenForCommand() {
        set(AssistantState.LISTENING)
        if (GoogleEar.canUse(app)) GoogleEar.listen(app, ::onCommand) else Ear.listenForCommand()
    }

    private fun onCommand(text: String) {
        OrbiState.lastHeard.value = text
        if (text.isBlank()) {
            speakPhrase("orbi_no_entendi", "No te entendí bien, ¿me lo repites?", listenAgain = false)
            return
        }
        if (SCENE.containsMatchIn(Ear.normalize(text))) {
            describeScene()
            return
        }
        val local = Commands.handle(app, text)
        if (local != null) {
            OrbiState.lastReply.value = local.text
            val after = { local.after?.invoke(); Unit }
            if (local.phrase != null && local.text.length < 40) speakPhrase(local.phrase, local.text, false, after)
            else speak(local.text, after)
            return
        }
        think(text)
    }

    private fun think(question: String) {
        if (!LocalBrain.isInstalled(app)) {
            speakPhrase("orbi_sin_cerebro", "Uy, todavía me falta mi cerebro. Revisa la app.", false)
            return
        }
        set(AssistantState.THINKING)
        OrbiState.lookHint.value = Offset(0.7f, -0.9f) // mira hacia arriba, pensando
        val started = SystemClock.elapsedRealtime()
        thinkJob = scope.launch {
            val reply = LocalBrain.ask(app, question)
            OrbiState.lookHint.value = null
            OrbiState.lastReply.value = reply
            android.util.Log.d("Assistant", "Gemma tardó ${SystemClock.elapsedRealtime() - started} ms")
            speak(reply)
        }
    }

    /** "¿Qué ves?", "¿qué hay aquí?", "mira esto"... */
    private val SCENE = Regex("""\b(que ves|que estas viendo|que hay (aqui|aca|ahi|alrededor)|que es esto|mira esto|describe|donde estoy)\b""")

    /** Mira por la cámara, reconoce objetos (ML Kit) y lo cuenta de forma natural (Gemma). */
    private fun describeScene() {
        if (!OrbiState.cameraAllowed.value || !OrbiState.trackingEnabled.value || !FaceTracker.isRunning) {
            speak("Necesito la cámara activada para poder ver. Actívala en Ajustes, en Seguir con la mirada.")
            return
        }
        set(AssistantState.THINKING)
        var answered = false
        val timeout = scope.launch {
            kotlinx.coroutines.delay(4000)
            if (!answered) { answered = true; speak("Uy, no logro ver bien. ¿Lo intentamos otra vez?") }
        }
        SceneSense.request { labels ->
            if (answered) return@request
            answered = true
            timeout.cancel()
            val things = labels.map { it.first }.distinct().take(5)
            if (things.isEmpty()) {
                speak("Mmm, no reconozco nada en especial. Quizá está muy oscuro o muy cerca.")
                return@request
            }
            if (!LocalBrain.isInstalled(app)) {
                speak("Veo: ${things.joinToString(", ")}.")
                return@request
            }
            OrbiState.lookHint.value = Offset(0.7f, -0.9f)
            thinkJob = scope.launch {
                val reply = LocalBrain.ask(
                    app,
                    "Estoy mirando por mi cámara y reconozco estas cosas: ${things.joinToString(", ")}. " +
                        "Si alguna palabra está en inglés, tradúcela. Cuéntame en una sola frase natural y simpática qué ves.",
                )
                OrbiState.lookHint.value = null
                OrbiState.lastReply.value = reply
                speak(reply)
            }
        }
    }

    private fun speak(text: String, after: () -> Unit = {}) {
        OrbiState.lastReply.value = text
        set(AssistantState.SPEAKING)
        Mouth.say(text) { done(); after() }
    }

    private fun speakPhrase(name: String, text: String, listenAgain: Boolean, after: () -> Unit = {}) {
        set(AssistantState.SPEAKING)
        Mouth.phrase(app, name, text) {
            if (listenAgain) listenForCommand() else { done(); after() }
        }
    }

    private fun done() {
        set(AssistantState.IDLE)
        waitForWakeWord()
    }

    /** Llegó la hora de un recordatorio: si Orbi está activo, lo dice en voz alta. */
    fun announceReminder(context: Context, text: String) {
        setup(context)
        Ear.stop()
        set(AssistantState.SPEAKING)
        OrbiState.attention.value = SystemClock.elapsedRealtime()
        Mouth.phrase(app, "orbi_recordatorio", "¡Oye! Tienes un recordatorio.") {
            Mouth.say(text) { done() }
        }
    }

    private fun set(state: AssistantState) {
        OrbiState.assistant.value = state
        // Mientras conversan, la isla superior se mantiene expandida.
        if (state != AssistantState.IDLE) OrbiState.attention.value = SystemClock.elapsedRealtime()
    }
}
