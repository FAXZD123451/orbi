package com.orbi.app.assistant

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.orbi.app.OrbiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * El cerebro de Orbi: Gemma 3 1B funcionando dentro del celular con MediaPipe.
 * Gratis, sin internet y privado: tus preguntas nunca salen del teléfono.
 *
 * El modelo (~550 MB) no viene dentro de la app: se instala aparte (ver [installFrom]).
 * Se carga la primera vez que hace falta y se mantiene en memoria mientras se use.
 */
object LocalBrain {
    private const val TAG = "LocalBrain"
    private const val MIN_SIZE = 100L * 1024 * 1024
    private const val MAX_REPLY_CHARS = 320
    private const val HISTORY = 3

    /** Cómo es Orbi. Respuestas cortas porque se dicen en voz alta. */
    private const val PERSONA =
        "Eres Orbi, un pequeño robot asistente que vive en el celular de tu dueño. " +
            "Hablas español latino, eres simpático, curioso y un poco juguetón. " +
            "Responde SIEMPRE en una o dos frases cortas, como si hablaras en voz alta: " +
            "sin listas, sin emojis, sin asteriscos ni formato. Si no sabes algo, dilo con honestidad."

    // Un solo hilo: el modelo no admite dos preguntas a la vez.
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var llm: LlmInference? = null
    private val history = ArrayDeque<Pair<String, String>>()

    /** En la memoria interna de la app: siempre es suya, así puede escribir el modelo al instalarlo. */
    fun modelFile(context: Context) = File(context.filesDir, "models/gemma.task")

    fun isInstalled(context: Context): Boolean {
        val f = modelFile(context)
        return (f.exists() && f.length() > MIN_SIZE).also { OrbiState.brainInstalled.value = it }
    }

    /** Pregunta algo a Gemma. Devuelve la respuesta ya lista para decir en voz alta. */
    suspend fun ask(context: Context, question: String): String = withContext(thread) {
        val engine = llm ?: LlmInference.createFromOptions(
            context.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile(context).absolutePath)
                .setMaxTokens(1024)
                .setMaxTopK(40)
                .build()
        ).also { llm = it }

        val prompt = buildString {
            append(PERSONA).append("\n\n")
            if (history.isNotEmpty()) {
                append("Conversación reciente:\n")
                history.forEach { (q, a) -> append("Usuario: $q\nOrbi: $a\n") }
                append("\n")
            }
            append("Usuario: ").append(question).append("\nOrbi:")
        }
        val raw = try {
            engine.generateResponse(prompt)
        } catch (e: Exception) {
            Log.w(TAG, "Gemma falló", e)
            return@withContext "Uy, se me trabó el cerebro. ¿Me lo preguntas otra vez?"
        }
        val reply = clean(raw)
        history.addLast(question to reply)
        while (history.size > HISTORY) history.removeFirst()
        reply
    }

    /** Quita formato que no se puede decir en voz alta y recorta respuestas largas. */
    private fun clean(text: String): String {
        var t = text
            .replace(Regex("<[^>]+>"), " ")           // marcas especiales del modelo
            .replace(Regex("^\\s*Orbi:\\s*"), "")
            .replace(Regex("[*_#`>]"), "")
            .replace(Regex("\\p{So}"), "")              // emojis
            .replace(Regex("\\s+"), " ")
            .trim()
        // Si se puso a imaginar el siguiente turno del usuario, se corta ahí.
        t = t.substringBefore("Usuario:").trim()
        if (t.length > MAX_REPLY_CHARS) {
            val cut = t.take(MAX_REPLY_CHARS)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            t = if (end > 60) cut.take(end + 1) else "$cut..."
        }
        return t.ifBlank { "Mmm, no sé qué decir." }
    }

    /** Libera la memoria del modelo (unos 1 GB) cuando no se usa. */
    fun unload() {
        llm?.close()
        llm = null
    }

    /**
     * Instala el modelo copiándolo desde un archivo que elijas (por ejemplo, de Descargas).
     * Va informando el progreso en [OrbiState.brainCopyProgress].
     */
    suspend fun installFrom(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile(context)
        val tmp = File(target.parentFile, "gemma.task.part")
        try {
            target.parentFile?.mkdirs()
            val total = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) OrbiState.brainCopyProgress.value = copied.toFloat() / total
                    }
                }
            }
            unload()
            target.delete()
            tmp.renameTo(target)
            isInstalled(context)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo instalar el modelo", e)
            tmp.delete()
            false
        } finally {
            OrbiState.brainCopyProgress.value = null
        }
    }
}
