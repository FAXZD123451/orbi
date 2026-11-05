package com.orbi.app.assistant

import android.content.Context
import android.content.Intent
import com.orbi.app.night.NightActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Una respuesta que Orbi sabe dar sin usar la IA. */
data class LocalReply(
    val text: String,
    /** Frase grabada que se usa en lugar de [text], si existe. */
    val phrase: String? = null,
    /** Algo que hacer después de responder (por ejemplo, abrir el modo noche). */
    val after: (() -> Unit)? = null,
    /** Termina la conversación sin volver a escuchar enseguida. */
    val quiet: Boolean = false,
)

/**
 * Órdenes que Orbi entiende al instante, sin preguntarle a Gemma: la hora, la fecha,
 * recordatorios, modo noche... Son más rápidas y nunca se equivocan con los números.
 *
 * El texto llega de Vosk en minúsculas, sin tildes y con los números en palabras
 * ("recuerdame tomar agua en diez minutos").
 */
object Commands {
    private val es = Locale.forLanguageTag("es")

    fun handle(context: Context, raw: String): LocalReply? {
        val t = Ear.normalize(raw)
        return when {
            Regex("""\b(que hora|la hora)\b""").containsMatchIn(t) -> time()
            Regex("""\b(que dia|que fecha|la fecha|dia es hoy)\b""").containsMatchIn(t) -> date()
            t.contains("recuerdame") || t.contains("recordatorio") -> reminder(context, t)
            Regex("""\b(buenas noches|modo noche|a dormir)\b""").containsMatchIn(t) ->
                LocalReply("Buenas noches, que descanses.", phrase = "orbi_buenas_noches", quiet = true, after = {
                    context.startActivity(Intent(context, NightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            Regex("""\b(gracias)\b""").containsMatchIn(t) ->
                LocalReply("¡De nada! Para eso estoy.", phrase = "orbi_gracias", quiet = true)
            Regex("""\b(adios|chao|hasta luego|nos vemos)\b""").containsMatchIn(t) ->
                LocalReply("¡Hasta luego!", phrase = "orbi_adios", quiet = true)
            Regex("""^(nada|olvidalo|cancela|callate|silencio)\b""").containsMatchIn(t) ->
                LocalReply("¡Vale!", phrase = "orbi_vale", quiet = true)
            else -> null
        }
    }

    private fun time(): LocalReply {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR)
        val hour = if (h == 0) 12 else h
        val min = c.get(Calendar.MINUTE)
        val part = when (c.get(Calendar.HOUR_OF_DAY)) {
            in 0..5 -> "de la madrugada"
            in 6..11 -> "de la mañana"
            in 12..18 -> "de la tarde"
            else -> "de la noche"
        }
        val es = if (hour == 1) "Es la una" else "Son las $hour"
        val minutes = when (min) { 0 -> " en punto"; 15 -> " y cuarto"; 30 -> " y media"; else -> " y $min" }
        return LocalReply("$es$minutes $part.")
    }

    private fun date(): LocalReply {
        val text = SimpleDateFormat("EEEE d 'de' MMMM", es).format(Calendar.getInstance().time)
        return LocalReply("Hoy es $text.")
    }

    // ---------- Recordatorios ----------

    private val NUMBERS = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "trece" to 13, "catorce" to 14, "quince" to 15, "veinte" to 20, "veinticinco" to 25,
        "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45, "cincuenta" to 50,
    )

    private fun number(word: String): Int? = word.toIntOrNull() ?: NUMBERS[word.trim()]

    /**
     * Entiende:
     *  - "recuerdame <algo> en <n> minutos|horas"   /  "en <n> minutos recuerdame <algo>"
     *  - "recuerdame <algo> en media hora"
     *  - "recuerdame <algo> a las <h> [y <m>] [de la tarde|noche]"
     */
    private fun reminder(context: Context, t: String): LocalReply {
        val numberWords = (NUMBERS.keys.sortedByDescending { it.length } + "\\d+").joinToString("|")
        val now = Calendar.getInstance()
        var at: Calendar? = null
        var what = t.replace(Regex("""^.*?recuerdame\s*(que\s+)?"""), "")

        Regex("""\ben media hora\b""").find(t)?.let {
            at = (now.clone() as Calendar).apply { add(Calendar.MINUTE, 30) }
            what = what.replace(it.value, "")
        }
        if (at == null) Regex("""\ben ($numberWords) (minuto|minutos|hora|horas)\b""").find(t)?.let { m ->
            val n = number(m.groupValues[1]) ?: return@let
            at = (now.clone() as Calendar).apply {
                add(if (m.groupValues[2].startsWith("hora")) Calendar.HOUR_OF_DAY else Calendar.MINUTE, n)
            }
            what = what.replace(m.value, "")
        }
        if (at == null) Regex("""\ba las ($numberWords)(?: y ($numberWords))?(?: (de la manana|de la tarde|de la noche))?\b""").find(t)?.let { m ->
            var h = number(m.groupValues[1]) ?: return@let
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
            val part = m.groupValues[3]
            if ((part == "de la tarde" || part == "de la noche") && h < 12) h += 12
            at = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0)
                // Sin "de la tarde": si esa hora ya pasó hoy, se asume la próxima vez que llegue.
                if (before(now)) {
                    if (part.isEmpty() && h < 12) add(Calendar.HOUR_OF_DAY, 12)
                    if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            what = what.replace(m.value, "")
        }

        what = what.replace(Regex("""\b(que|de|para)\s*$"""), "").replace(Regex("\\s+"), " ").trim()
        val time = at ?: return LocalReply("¿Para cuándo? Dime, por ejemplo: recuérdame tomar agua en diez minutos.")
        if (what.isEmpty()) what = "lo que me pediste"

        Reminders.schedule(context, what, time.timeInMillis)
        val hour = SimpleDateFormat("h:mm a", es).format(time.time)
        return LocalReply("¡Listo! A las $hour te recuerdo: $what.")
    }
}
