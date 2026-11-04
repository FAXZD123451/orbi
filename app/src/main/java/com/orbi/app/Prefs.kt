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
    private const val MODE = "bubble_mode"
    private const val FLOAT_SIZE = "float_size_dp"
    private const val BAR_HEIGHT = "bar_height_dp"

    private lateinit var sp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("orbi", Context.MODE_PRIVATE)
        OrbiState.trackingEnabled.value = sp.getBoolean(TRACKING, true)
        OrbiState.soundEnabled.value = sp.getBoolean(SOUND, true)
        OrbiState.bubbleMode.value =
            runCatching { BubbleMode.valueOf(sp.getString(MODE, null)!!) }.getOrDefault(BubbleMode.FLOATING)
        OrbiState.floatSizeDp.value = sp.getFloat(FLOAT_SIZE, FLOAT_SIZE_DEFAULT)
        OrbiState.barHeightDp.value = sp.getFloat(BAR_HEIGHT, BAR_HEIGHT_DEFAULT)
        // Cada vez que cambian los interruptores o el tamaño, se guardan solos.
        scope.launch { OrbiState.trackingEnabled.drop(1).collect { v -> sp.edit { putBoolean(TRACKING, v) } } }
        scope.launch { OrbiState.soundEnabled.drop(1).collect { v -> sp.edit { putBoolean(SOUND, v) } } }
        scope.launch { OrbiState.bubbleMode.drop(1).collect { v -> sp.edit { putString(MODE, v.name) } } }
        scope.launch { OrbiState.floatSizeDp.drop(1).collect { v -> sp.edit { putFloat(FLOAT_SIZE, v) } } }
        scope.launch { OrbiState.barHeightDp.drop(1).collect { v -> sp.edit { putFloat(BAR_HEIGHT, v) } } }
    }

    const val FLOAT_SIZE_MIN = 64f
    const val FLOAT_SIZE_MAX = 200f
    const val FLOAT_SIZE_DEFAULT = 96f
    const val BAR_HEIGHT_MIN = 90f
    const val BAR_HEIGHT_MAX = 260f
    const val BAR_HEIGHT_DEFAULT = 150f

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
