package com.orbi.app.assistant

import android.content.Context
import android.util.Log
import com.orbi.app.OrbiState
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * El oído de Orbi: reconocimiento de voz con Vosk, dentro del celular y sin internet.
 *
 * Tiene dos modos:
 *  - WAKE: transcribe en silencio lo que oye y solo reacciona si aparece "Oye Orbi".
 *    Nada se guarda ni se envía: el texto se descarta al momento.
 *  - COMMAND: tras despertar, escucha tu frase completa y se la pasa al asistente.
 */
object Ear {
    private const val TAG = "Ear"
    private const val SAMPLE_RATE = 16_000f
    private const val COMMAND_TIMEOUT_MS = 9_000

    /**
     * El modelo pequeño en español quizá no conoce la palabra "orbi" y la escribe como algo
     * parecido. Se aceptan las variantes más comunes (se pueden afinar viendo el registro).
     */
    private val WAKE = Regex("""\b(orbi|orbe|orbis|orbit|orby|orvi|orve|obi|orbita)\b""")

    private var model: Model? = null
    private var service: SpeechService? = null
    private val loader = Executors.newSingleThreadExecutor()

    /** Se llama al oír "Oye Orbi". [rest] es lo que dijiste justo después, si fue de corrido. */
    var onWake: ((rest: String) -> Unit)? = null
    /** Se llama con tu frase completa (vacía si no dijiste nada a tiempo). */
    var onCommand: ((text: String) -> Unit)? = null

    fun modelDir(context: Context) = File(context.getExternalFilesDir(null), "models/vosk-es")
    fun isInstalled(context: Context) = File(modelDir(context), "am/final.mdl").exists()

    /** Carga el modelo en segundo plano (tarda un par de segundos la primera vez). */
    fun load(context: Context, done: (Boolean) -> Unit) {
        if (model != null) { done(true); return }
        val dir = modelDir(context)
        loader.execute {
            val ok = try {
                model = Model(dir.absolutePath)
                true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar el modelo de Vosk", e)
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                OrbiState.earReady.value = ok
                done(ok)
            }
        }
    }

    fun listenForWake() = start(command = false)

    fun listenForCommand() = start(command = true)

    fun stop() {
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
    }

    private fun start(command: Boolean) {
        stop()
        val m = model ?: return
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            service = SpeechService(recognizer, SAMPLE_RATE).also { s ->
                val listener = if (command) CommandListener() else WakeListener()
                if (command) s.startListening(listener, COMMAND_TIMEOUT_MS) else s.startListening(listener)
            }
        } catch (e: Exception) {
            // Por ejemplo, el micrófono está ocupado por otra app (una llamada, una nota de voz...).
            Log.w(TAG, "No se pudo abrir el micrófono", e)
            service = null
        }
    }

    /** Pasa a minúsculas y quita tildes para comparar fácil. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun textOf(json: String?, key: String) =
        try { JSONObject(json ?: "{}").optString(key, "") } catch (e: Exception) { "" }

    private class WakeListener : RecognitionListener {
        private var fired = false

        private fun check(text: String, final: Boolean) {
            if (fired || text.isBlank()) return
            val clean = normalize(text)
            val match = WAKE.find(clean) ?: return
            // En resultados parciales se espera al final de la frase si dijo algo más después.
            val rest = clean.substring(match.range.last + 1).trim()
            if (!final && rest.isEmpty()) {
                Log.d(TAG, "oí la palabra clave: \"$clean\"")
                fired = true
                stop()
                onWake?.invoke("")
            } else if (final) {
                Log.d(TAG, "oí la palabra clave con orden: \"$clean\"")
                fired = true
                stop()
                onWake?.invoke(rest)
            }
        }

        override fun onPartialResult(hypothesis: String?) = check(textOf(hypothesis, "partial"), final = false)
        override fun onResult(hypothesis: String?) = check(textOf(hypothesis, "text"), final = true)
        override fun onFinalResult(hypothesis: String?) = check(textOf(hypothesis, "text"), final = true)
        override fun onError(exception: Exception?) { Log.w(TAG, "error escuchando", exception) }
        override fun onTimeout() = Unit
    }

    private class CommandListener : RecognitionListener {
        private var done = false

        private fun finish(text: String) {
            if (done) return
            done = true
            stop()
            Log.d(TAG, "orden: \"$text\"")
            onCommand?.invoke(text)
        }

        override fun onPartialResult(hypothesis: String?) = Unit
        // onResult llega cuando te quedas callado tras hablar: esa es tu frase.
        override fun onResult(hypothesis: String?) {
            val text = textOf(hypothesis, "text")
            if (text.isNotBlank()) finish(text)
        }
        override fun onFinalResult(hypothesis: String?) = finish(textOf(hypothesis, "text"))
        override fun onError(exception: Exception?) = finish("")
        override fun onTimeout() = finish("")
    }
}
