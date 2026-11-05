package com.orbi.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.orbi.app.OrbiState
import java.util.Locale

/**
 * Oído para tus preguntas: el reconocimiento de voz de Google que trae Android, mucho más
 * preciso que el modelo pequeño de Vosk (que se queda solo para detectar "Oye Orbi").
 *
 * Orden de preferencia:
 *  1. Dentro del celular (Android 12+), sin internet.
 *  2. Si el celular no lo tiene (o le falta el idioma): la versión en línea de Google,
 *     solo si está permitido en Ajustes ([OrbiState.onlineSpeech]).
 *  3. Si nada de eso: se devuelve null y el asistente usa Vosk.
 */
object GoogleEar {
    private const val TAG = "GoogleEar"
    private var recognizer: SpeechRecognizer? = null
    private var triedModelDownload = false

    private fun onDeviceAvailable(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun onlineAvailable(context: Context) =
        OrbiState.onlineSpeech.value && SpeechRecognizer.isRecognitionAvailable(context)

    /** ¿Se puede usar algún reconocedor de Google? */
    fun canUse(context: Context) = onDeviceAvailable(context) || onlineAvailable(context)

    /** Español de tu teléfono si lo tienes; si no, español latinoamericano. */
    private fun language(): String {
        val l = Locale.getDefault()
        return if (l.language == "es") l.toLanguageTag() else "es-419"
    }

    private fun intent(onDevice: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Espera un poco más antes de dar la frase por terminada (las pausas al pensar).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    /** Escucha una frase y devuelve el texto ("" si no se entendió nada). Debe llamarse en el hilo principal. */
    fun listen(context: Context, onResult: (String) -> Unit) {
        val useDevice = onDeviceAvailable(context)
        start(context, useDevice, onResult)
    }

    private fun start(context: Context, onDevice: Boolean, onResult: (String) -> Unit) {
        stop()
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        Log.d(TAG, if (onDevice) "escuchando (dentro del celular)" else "escuchando (Google en línea)")
        var delivered = false
        fun deliver(text: String) {
            if (delivered) return
            delivered = true
            stop()
            OrbiState.earHearing.value = ""
            onResult(text)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.d(TAG, "entendí: \"$text\"")
                deliver(text)
            }

            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) OrbiState.earHearing.value = text // subtítulo en vivo
            }

            override fun onError(error: Int) {
                Log.d(TAG, "error $error (${if (onDevice) "en el celular" else "en línea"})")
                val missingLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (onDevice && missingLanguage) {
                    // Pide a Android que descargue el español para la próxima vez...
                    if (!triedModelDownload && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        triedModelDownload = true
                        runCatching { r.triggerModelDownload(intent(true)) }
                    }
                    // ...y por ahora prueba en línea, si está permitido.
                    if (onlineAvailable(context)) {
                        delivered = true // esta escucha se reemplaza por la nueva
                        stop()
                        start(context, onDevice = false, onResult)
                        return
                    }
                }
                deliver("")
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent(onDevice))
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}
