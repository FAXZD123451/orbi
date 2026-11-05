package com.orbi.app.brain

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import com.orbi.app.Cue
import com.orbi.app.CueType
import com.orbi.app.OrbiState
import com.orbi.app.face.Expression
import com.orbi.app.sound.Sfx
import com.orbi.app.sound.SoundFx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

/**
 * El "cerebro" de Orbi: decide qué cara poner (y qué sonido hacer) según lo que pasa.
 *
 * Funciona en dos capas:
 *  1. Reacciones: secuencias cortas por algo que acaba de pasar (lo tocas, lo sacudes,
 *     lo lanzas, le guiñas, te ve llegar...). Mandan mientras duran.
 *  2. Ánimo de fondo: lo que siente cuando no pasa nada (contento si lo acaricias, asustado
 *     si le tapas la cámara, dormido si nadie le hace caso, feliz si sonríes...).
 *
 * Cada 100 ms revisa todo y, si cambió, escribe la nueva expresión en [OrbiState].
 */
object MoodEngine {
    private const val TICK_MS = 100L
    private const val SLEEP_AFTER_MS = 2 * 60_000L
    private const val SLEEP_AFTER_NIGHT_MS = 45_000L
    private const val GREET_AFTER_ABSENCE_MS = 20_000L
    private const val ANGRY_TAPS = 5
    private const val ANGRY_WINDOW_MS = 3_000L
    private const val ANGRY_SHAKES = 3
    private const val SHAKE_WINDOW_MS = 5_000L
    private const val PET_HOLD_MS = 500L
    private const val COVERED_MAX_MS = 15_000L

    private data class Step(val expression: Expression, val ms: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sequence = ArrayDeque<Step>()
    private var stepUntil = 0L
    private val taps = ArrayDeque<Long>()
    private val shakes = ArrayDeque<Long>()

    private var lastInteraction = now()
    private var lastFaceSeen = 0L
    private var hadFace = false
    private var lastSearching = false
    private var nextIdleMood = now() + Random.nextLong(20_000, 45_000)
    private var petting = false
    private var lastPetMove = 0L
    private var lastPurrSound = 0L
    private var coveredSince = 0L
    private var hintJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    // ---------- Cosas que le pasan a Orbi ----------

    /** Lo tocaron (burbuja o app). Muchos toques seguidos lo enojan. */
    fun onTap() {
        val t = touch()
        val wasAsleep = isDozing()
        taps.addLast(t)
        while (taps.isNotEmpty() && taps.first() < t - ANGRY_WINDOW_MS) taps.removeFirst()

        when {
            wasAsleep -> play(Sfx.SURPRISED, Step(Expression.SURPRISED, 600), Step(Expression.HAPPY, 1200))
            taps.size >= ANGRY_TAPS -> {
                taps.clear()
                play(Sfx.ANGRY, Step(Expression.ANGRY, 1800))
            }
            else -> play(Sfx.HAPPY, Step(Expression.HAPPY, 1300))
        }
    }

    /** Sacudiste el celular: se marea. Si insistes, se enoja. */
    fun onShake() {
        val t = touch()
        shakes.addLast(t)
        while (shakes.isNotEmpty() && shakes.first() < t - SHAKE_WINDOW_MS) shakes.removeFirst()
        if (shakes.size >= ANGRY_SHAKES) {
            shakes.clear()
            play(Sfx.ANGRY, Step(Expression.DIZZY, 900), Step(Expression.ANGRY, 1800))
        } else {
            play(Sfx.DIZZY, Step(Expression.DIZZY, 2600))
        }
    }

    /** Lanzaste la burbuja: va gritando "¡wiii!" mientras vuela. */
    fun onFlingStart() {
        touch()
        play(Sfx.WEE, Step(Expression.SURPRISED, 4000))
    }

    /** La burbuja aterrizó tras chocar [bounces] veces con los bordes. */
    fun onFlingEnd(bounces: Int) {
        touch()
        if (bounces >= 2) play(Sfx.DIZZY, Step(Expression.DIZZY, 2200), Step(Expression.HAPPY, 900))
        else play(Sfx.HAPPY, Step(Expression.HAPPY, 1400))
    }

    /** Empezaste a acariciarlo (mantener y frotar). */
    fun onPetStart() {
        touch()
        petting = true
        lastPetMove = now()
        sequence.clear()
    }

    fun onPetting() {
        val t = touch()
        lastPetMove = t
        if (t - lastPurrSound > 1400) {
            lastPurrSound = t
            SoundFx.play(Sfx.PURR)
        }
    }

    fun onPetEnd() {
        touch()
        if (petting && now() - lastPetMove < PET_HOLD_MS) play(null, Step(Expression.HAPPY, 1200))
        petting = false
    }

    /** Le guiñaste un ojo: te lo devuelve con el mismo lado (como un espejo). */
    fun onWink(side: Int) {
        touch()
        OrbiState.winkSide.value = side
        play(Sfx.WINK, Step(Expression.WINK, 900), Step(Expression.HAPPY, 700))
    }

    /** Le tapaste la cámara: se asusta y te busca. */
    fun onCovered() {
        touch()
        coveredSince = now()
        play(Sfx.SCARED, Step(Expression.SCARED, 600))
    }

    /** Quitaste la mano: "¡te encontré!". */
    fun onUncovered() {
        val t = touch()
        if (coveredSince == 0L) return
        coveredSince = 0L
        lastFaceSeen = t // para que no lo cuente además como "llegaste"
        play(Sfx.FOUND, Step(Expression.SURPRISED, 500), Step(Expression.HAPPY, 1500))
    }

    /** Llegó una notificación de otra app: se sorprende y mira hacia arriba, donde aparece. */
    fun onNotification() {
        if (OrbiState.nightMode.value) return
        OrbiState.attention.value = now() // la isla superior se expande para avisarte
        play(Sfx.NOTIFY, Step(Expression.SURPRISED, 900), Step(Expression.NEUTRAL, 900))
        hintJob?.cancel()
        hintJob = scope.launch {
            OrbiState.lookHint.value = Offset(0.3f, -1f)
            delay(1800)
            OrbiState.lookHint.value = null
        }
    }

    /** Lo tocaron en modo noche: se despierta un momento y vuelve a dormir. */
    fun onNightTap() {
        touch()
        play(Sfx.SURPRISED, Step(Expression.SURPRISED, 700), Step(Expression.HAPPY, 1500), Step(Expression.NEUTRAL, 4000))
    }

    /** Abriste la app: cuenta como que estás con él. */
    fun onAppOpened() {
        val wasAsleep = isDozing()
        touch()
        if (wasAsleep) play(Sfx.SURPRISED, Step(Expression.SURPRISED, 600), Step(Expression.HAPPY, 1000))
    }

    /** Pusiste el celular boca abajo: bosteza y se echa una siesta. */
    fun onFaceDown() {
        touch()
        play(Sfx.YAWN, Step(Expression.DROWSY, 900))
    }

    /** Lo levantaste: se despierta sorprendido y contento. */
    fun onPickedUp() {
        touch()
        play(Sfx.SURPRISED, Step(Expression.SURPRISED, 600), Step(Expression.HAPPY, 1200))
    }

    /** Apagaste la luz del cuarto: le da sueño. */
    fun onLightsOff() {
        if (OrbiState.faceDown.value) return
        play(Sfx.YAWN, Step(Expression.DROWSY, 1500))
    }

    /** Prendiste la luz: se sorprende (¡qué brillo!). */
    fun onLightsOn() {
        if (OrbiState.faceDown.value) return
        touch()
        play(Sfx.SURPRISED, Step(Expression.SURPRISED, 700), Step(Expression.NEUTRAL, 400))
    }

    /** Asentiste con la cabeza: "¡sí!". Orbi asiente también. */
    fun onNod() {
        touch()
        cue(CueType.NOD)
        play(Sfx.HAPPY, Step(Expression.HAPPY, 1300))
    }

    /** Negaste con la cabeza: "no". Orbi niega también, un poco triste. */
    fun onHeadShake() {
        touch()
        cue(CueType.HEAD_SHAKE)
        play(null, Step(Expression.SAD, 1400))
    }

    private var cueId = 0L
    private fun cue(type: CueType) {
        OrbiState.cue.value = Cue(type, ++cueId)
    }

    /** Bostezaste frente a él: los bostezos se contagian. */
    fun onYawnSeen() {
        touch()
        play(Sfx.YAWN, Step(Expression.SURPRISED, 400), Step(Expression.DROWSY, 1800))
    }

    /** Te acercaste mucho a la pantalla: se echa para atrás, sorprendido. */
    fun onTooClose() {
        touch()
        play(Sfx.SURPRISED, Step(Expression.SURPRISED, 900), Step(Expression.HAPPY, 800))
    }

    /** Lo miraste fijo un buen rato: le da vergüenza, sonríe y mira hacia otro lado. */
    fun onStare() {
        touch()
        play(Sfx.WINK, Step(Expression.HAPPY, 1800))
        glanceAway(Offset(if (Random.nextBoolean()) 1f else -1f, 0.4f), 1600)
    }

    /** "Toc, toc": golpecitos en el celular. Se pregunta quién es y mira a su alrededor. */
    fun onKnock() {
        val wasAsleep = isDozing()
        touch()
        play(Sfx.SURPRISED, Step(Expression.SURPRISED, 700), Step(if (wasAsleep) Expression.DROWSY else Expression.NEUTRAL, 900))
        glanceAway(Offset(-1f, -0.5f), 500)
    }

    /** El celular está cayendo (o lo lanzaste al aire): grita asustado. */
    fun onFreeFall() {
        touch()
        play(Sfx.WEE, Step(Expression.SCARED, 3000))
    }

    /** Aterrizó tras una caída: queda mareado. */
    fun onLanded() {
        touch()
        play(Sfx.DIZZY, Step(Expression.DIZZY, 2200), Step(Expression.SAD, 800))
    }

    /** Mira un momento hacia [where] y luego vuelve. */
    private fun glanceAway(where: Offset, ms: Long) {
        hintJob?.cancel()
        hintJob = scope.launch {
            OrbiState.lookHint.value = where
            delay(ms)
            OrbiState.lookHint.value = null
        }
    }

    /** Se alegra por algo (por ejemplo, avanzar en la bienvenida). No cuenta como "toque". */
    fun cheer() {
        touch()
        play(Sfx.HAPPY, Step(Expression.HAPPY, 1000))
    }

    fun onCharging() {
        touch()
        play(Sfx.HAPPY, Step(Expression.SURPRISED, 400), Step(Expression.HAPPY, 2000))
    }

    // ---------- Lógica ----------

    private fun touch(): Long = now().also { lastInteraction = it }

    private fun play(sound: Sfx?, vararg steps: Step) {
        sequence.clear()
        sequence.addAll(steps)
        stepUntil = 0L
        sound?.let(SoundFx::play)
    }

    // ---- Ánimo de fondo estable: un cambio tiene que durar BG_HOLD_MS para aplicarse ----
    private const val BG_HOLD_MS = 500L
    /** Estos cambian al instante porque responden a algo que haces en ese momento. */
    private val IMMEDIATE = setOf(Expression.PURR, Expression.SCARED)
    private var bgCurrent = Expression.NEUTRAL
    private var bgCandidate = Expression.NEUTRAL
    private var bgSince = 0L

    private fun stableBackground(t: Long): Expression {
        val raw = background(t)
        if (raw == bgCurrent) {
            bgCandidate = raw
            return bgCurrent
        }
        if (raw in IMMEDIATE) {
            bgCurrent = raw
            bgCandidate = raw
            return raw
        }
        if (raw != bgCandidate) {
            bgCandidate = raw
            bgSince = t
        }
        if (t - bgSince >= BG_HOLD_MS) bgCurrent = raw
        return bgCurrent
    }

    private fun tick() {
        val t = now()
        watchFace(t)

        val reaction = currentReaction(t)
        val expression = reaction ?: stableBackground(t)

        if (reaction == null && expression == Expression.NEUTRAL && t >= nextIdleMood) idleMood()
        if (OrbiState.expression.value != expression) {
            if (expression == Expression.SLEEPING && !OrbiState.nightMode.value && !OrbiState.faceDown.value &&
                OrbiState.expression.value !in setOf(Expression.SLEEPING, Expression.DROWSY)) SoundFx.play(Sfx.YAWN)
            OrbiState.expression.value = expression
        }
    }

    /** Si la cámara te acaba de ver aparecer, te saluda (o se despierta). */
    private fun watchFace(t: Long) {
        val face = OrbiState.faceVisible.value
        val wasSearching = lastSearching
        lastSearching = OrbiState.searching.value
        if (face && !hadFace && !OrbiState.nightMode.value && coveredSince == 0L) {
            if (isDozing()) {
                play(Sfx.SURPRISED, Step(Expression.SURPRISED, 600), Step(Expression.HAPPY, 1400))
            } else if (wasSearching) {
                // Te estaba buscando y por fin te encontró: "¡ahí estás!".
                play(Sfx.FOUND, Step(Expression.SURPRISED, 350), Step(Expression.HAPPY, 1300))
            } else if (t - lastFaceSeen > GREET_AFTER_ABSENCE_MS) {
                play(Sfx.HAPPY, Step(Expression.HAPPY, 1400))
            }
        }
        if (face) lastFaceSeen = t
        hadFace = face
    }

    private fun currentReaction(t: Long): Expression? {
        while (sequence.isNotEmpty()) {
            if (stepUntil == 0L) stepUntil = t + sequence.first().ms
            if (t < stepUntil) return sequence.first().expression
            sequence.removeFirst()
            stepUntil = 0L
        }
        return null
    }

    private fun background(t: Long): Expression {
        if (petting && t - lastPetMove < PET_HOLD_MS) return Expression.PURR
        if (OrbiState.faceDown.value) return Expression.SLEEPING
        if (coveredSince != 0L) {
            if (t - coveredSince < COVERED_MAX_MS) return Expression.SCARED
            coveredSince = 0L // se cansó de buscarte
        }
        if (OrbiState.nightMode.value) return Expression.SLEEPING
        if (isIdle(t)) return Expression.SLEEPING
        if (OrbiState.faceVisible.value && OrbiState.userEyesClosed.value) return Expression.DROWSY // se adormece contigo
        if (OrbiState.faceVisible.value && OrbiState.mouthOpen.value) return Expression.SURPRISED // te imita
        if (OrbiState.smiling.value) return Expression.HAPPY
        if (OrbiState.roomDark.value) return Expression.DROWSY
        if (OrbiState.batteryLow.value) return Expression.SAD
        return Expression.NEUTRAL
    }

    /** De vez en cuando cambia de cara solo, para que se sienta vivo. */
    private fun idleMood() {
        nextIdleMood = now() + Random.nextLong(25_000, 60_000)
        when (Random.nextInt(10)) {
            in 0..5 -> play(null, Step(Expression.HAPPY, 1200))
            in 6..7 -> play(null, Step(Expression.SURPRISED, 700), Step(Expression.NEUTRAL, 300))
            else -> play(null, Step(Expression.SLEEPING, 1500), Step(Expression.SURPRISED, 500)) // "cabeceo"
        }
    }

    private fun isIdle(t: Long): Boolean {
        val limit = if (isNightHour()) SLEEP_AFTER_NIGHT_MS else SLEEP_AFTER_MS
        return t - maxOf(lastInteraction, lastFaceSeen) > limit
    }

    /** Dormido por aburrimiento (no por modo noche). */
    private fun isDozing() = !OrbiState.nightMode.value && OrbiState.expression.value == Expression.SLEEPING

    private fun isNightHour(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6
    }

    private fun now() = SystemClock.elapsedRealtime()
}
