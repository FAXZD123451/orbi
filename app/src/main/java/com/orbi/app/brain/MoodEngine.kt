package com.orbi.app.brain

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
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

    private fun tick() {
        val t = now()
        watchFace(t)

        val reaction = currentReaction(t)
        val expression = reaction ?: background(t)

        if (reaction == null && expression == Expression.NEUTRAL && t >= nextIdleMood) idleMood()
        if (OrbiState.expression.value != expression) {
            if (expression == Expression.SLEEPING && !OrbiState.nightMode.value &&
                OrbiState.expression.value != Expression.SLEEPING) SoundFx.play(Sfx.YAWN)
            OrbiState.expression.value = expression
        }
    }

    /** Si la cámara te acaba de ver aparecer, te saluda (o se despierta). */
    private fun watchFace(t: Long) {
        val face = OrbiState.faceVisible.value
        if (face && !hadFace && !OrbiState.nightMode.value && coveredSince == 0L) {
            if (isDozing()) {
                play(Sfx.SURPRISED, Step(Expression.SURPRISED, 600), Step(Expression.HAPPY, 1400))
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
        if (coveredSince != 0L) {
            if (t - coveredSince < COVERED_MAX_MS) return Expression.SCARED
            coveredSince = 0L // se cansó de buscarte
        }
        if (OrbiState.nightMode.value) return Expression.SLEEPING
        if (isIdle(t)) return Expression.SLEEPING
        if (OrbiState.smiling.value) return Expression.HAPPY
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
