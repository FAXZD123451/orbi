package com.orbi.app.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * La voz de Orbi.
 *  - Frases fijas ("¿Sí?", "¡Listo!"...): audios grabados una vez con ElevenLabs, en res/raw.
 *    Si un audio no existe, se dice con la voz del sistema.
 *  - Respuestas libres: voz de Android (gratis y sin internet), con tono más agudo y algo
 *    más rápida para que suene a robot simpático.
 */
object Mouth {
    private const val TAG = "Mouth"
    private const val PITCH = 1.35f
    private const val RATE = 1.08f

    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "La voz del sistema no está disponible")
                return@TextToSpeech
            }
            // Español latino si está instalado; si no, cualquier español.
            val latam = engine.setLanguage(Locale.forLanguageTag("es-US"))
            if (latam < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.forLanguageTag("es"))
            engine.setPitch(PITCH)
            engine.setSpeechRate(RATE)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) = finish(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = finish(id)
                override fun onError(id: String?, errorCode: Int) = finish(id)
            })
            ready = true
        }
    }

    private fun finish(id: String?) {
        val callback = synchronized(pending) { pending.remove(id) } ?: return
        main.post(callback)
    }

    /** Dice un texto libre con la voz del sistema. */
    fun say(text: String, onDone: () -> Unit = {}) {
        stopPlayer()
        val engine = tts
        if (!ready || engine == null || text.isBlank()) {
            main.post(onDone)
            return
        }
        val id = "orbi-${++counter}"
        synchronized(pending) { pending[id] = onDone }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Reproduce una frase grabada (res/raw/[name]); si no existe, dice [fallback] con la voz del sistema. */
    @SuppressLint("DiscouragedApi")
    fun phrase(context: Context, name: String, fallback: String, onDone: () -> Unit = {}) {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) {
            say(fallback, onDone)
            return
        }
        stopPlayer()
        tts?.stop()
        val mp = MediaPlayer.create(context, id)
        if (mp == null) {
            say(fallback, onDone)
            return
        }
        player = mp
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            onDone()
        }
        mp.start()
    }

    fun stop() {
        stopPlayer()
        tts?.stop()
        synchronized(pending) { pending.clear() }
    }

    private fun stopPlayer() {
        player?.let {
            it.setOnCompletionListener(null)
            it.stop()
            it.release()
        }
        player = null
    }
}
