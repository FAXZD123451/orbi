package com.orbi.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Lo que Orbi recuerda aunque cierres la app o reinicies el celular. */
object Prefs {
    private const val TRACKING = "tracking"
    private const val SOUND = "sound"
    private const val ONBOARDED = "onboarded"
    private const val BUBBLE_WANTED = "bubble_wanted"
    private const val BUBBLE_X = "bubble_x"
    private const val BUBBLE_Y = "bubble_y"

    private lateinit var sp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("orbi", Context.MODE_PRIVATE)
        OrbiState.trackingEnabled.value = sp.getBoolean(TRACKING, true)
        OrbiState.soundEnabled.value = sp.getBoolean(SOUND, true)
        // Cada vez que cambian los interruptores, se guardan solos.
        scope.launch { OrbiState.trackingEnabled.drop(1).collect { v -> sp.edit { putBoolean(TRACKING, v) } } }
        scope.launch { OrbiState.soundEnabled.drop(1).collect { v -> sp.edit { putBoolean(SOUND, v) } } }
    }

    /** Ya vio la pantalla de bienvenida. */
    var onboarded: Boolean
        get() = sp.getBoolean(ONBOARDED, false)
        set(v) = sp.edit { putBoolean(ONBOARDED, v) }

    /** El usuario dejó la burbuja abierta (para traerla de vuelta si Android la cierra o al reiniciar). */
    var bubbleWanted: Boolean
        get() = sp.getBoolean(BUBBLE_WANTED, false)
        set(v) = sp.edit { putBoolean(BUBBLE_WANTED, v) }

    fun bubblePosition(): Pair<Int, Int>? =
        if (sp.contains(BUBBLE_X)) sp.getInt(BUBBLE_X, 0) to sp.getInt(BUBBLE_Y, 0) else null

    fun saveBubblePosition(x: Int, y: Int) = sp.edit { putInt(BUBBLE_X, x); putInt(BUBBLE_Y, y) }
}
