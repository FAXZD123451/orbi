package com.orbi.app.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.orbi.app.OrbiState
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Los "bips" de Orbi. */
enum class Sfx { HAPPY, SURPRISED, ANGRY, DIZZY, PURR, WINK, SCARED, FOUND, WEE, NOTIFY, YAWN }

/**
 * Sintetiza los sonidos de Orbi con código: tonos que suben, bajan o vibran, como un robot
 * pequeño. No usa archivos de audio, así que todos los sonidos son propios.
 */
object SoundFx {
    private const val RATE = 22_050
    private const val MIN_GAP_MS = 180L

    /** Un tramo de sonido: barre de f0 a f1 Hz durante ms, con vibrato opcional. f0 = 0 es silencio. */
    private data class Tone(
        val f0: Float,
        val f1: Float = f0,
        val ms: Int,
        val vibratoHz: Float = 0f,
        val vibratoDepth: Float = 0f,
        val volume: Float = 0.35f,
        /** Mezcla armónicos impares: suena más "electrónico". */
        val buzzy: Boolean = false,
    )

    private val recipes: Map<Sfx, List<Tone>> = mapOf(
        Sfx.HAPPY to listOf(Tone(700f, 1100f, 70), Tone(0f, ms = 30), Tone(900f, 1500f, 90)),
        Sfx.SURPRISED to listOf(Tone(500f, 1400f, 120)),
        Sfx.ANGRY to listOf(Tone(220f, 150f, 280, vibratoHz = 25f, vibratoDepth = 0.08f, buzzy = true)),
        Sfx.DIZZY to listOf(Tone(950f, 280f, 650, vibratoHz = 9f, vibratoDepth = 0.15f)),
        Sfx.PURR to listOf(Tone(170f, 210f, 520, vibratoHz = 28f, vibratoDepth = 0.25f, volume = 0.22f, buzzy = true)),
        Sfx.WINK to listOf(Tone(1200f, 1700f, 55)),
        Sfx.SCARED to listOf(Tone(1300f, 1000f, 320, vibratoHz = 18f, vibratoDepth = 0.1f)),
        Sfx.FOUND to listOf(Tone(660f, ms = 70), Tone(880f, ms = 70), Tone(1320f, ms = 110)),
        Sfx.WEE to listOf(Tone(600f, 1800f, 360)),
        Sfx.NOTIFY to listOf(Tone(1000f, ms = 60), Tone(0f, ms = 40), Tone(1300f, ms = 70)),
        Sfx.YAWN to listOf(Tone(500f, 260f, 600, vibratoHz = 5f, vibratoDepth = 0.05f, volume = 0.2f)),
    )

    private val cache = HashMap<Sfx, ShortArray>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastPlay = 0L

    fun play(sfx: Sfx) {
        if (!OrbiState.soundEnabled.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlay < MIN_GAP_MS) return
        lastPlay = now
        executor.execute {
            val pcm = cache.getOrPut(sfx) { synth(recipes.getValue(sfx)) }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            }, main)
            track.play()
        }
    }

    private fun synth(tones: List<Tone>): ShortArray {
        val total = tones.sumOf { it.ms } * RATE / 1000
        val out = ShortArray(total)
        var i = 0
        var phase = 0.0
        for (tone in tones) {
            val n = tone.ms * RATE / 1000
            val attack = min(n / 4, RATE * 5 / 1000)
            val release = min(n / 3, RATE * 25 / 1000)
            for (k in 0 until n) {
                if (i >= out.size) break
                if (tone.f0 <= 0f) { out[i++] = 0; continue }
                val t = k / n.toFloat()
                // Barrido exponencial (suena más natural que uno lineal).
                var f = tone.f0 * exp(ln(tone.f1 / tone.f0) * t)
                if (tone.vibratoHz > 0f) {
                    f *= 1f + tone.vibratoDepth * sin(2 * PI * tone.vibratoHz * k / RATE).toFloat()
                }
                phase += 2 * PI * f / RATE
                var s = sin(phase)
                if (tone.buzzy) s = s * 0.7 + sin(3 * phase) * 0.22 + sin(5 * phase) * 0.08
                val env = when {
                    k < attack -> k / attack.toFloat()
                    k > n - release -> (n - k) / release.toFloat()
                    else -> 1f
                }
                out[i++] = (s * env * tone.volume * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
