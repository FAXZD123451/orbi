package com.orbi.app.night

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine
import com.orbi.app.face.LiveOrbiFace
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Modo noche: Orbi duerme a pantalla completa, con la pantalla encendida y el brillo al mínimo.
 * Tocar lo despierta un momento (lo decide [MoodEngine]); mantener presionado sale del modo.
 * (En una fase siguiente aquí se activará la vigilancia con la cámara.)
 */
class NightActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.05f }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { NightScreen(onExit = ::finish) }
    }

    override fun onResume() {
        super.onResume()
        OrbiState.nightMode.value = true
    }

    override fun onPause() {
        OrbiState.nightMode.value = false
        super.onPause()
    }
}

@Composable
private fun NightScreen(onExit: () -> Unit) {
    // La cara la decide el cerebro: en modo noche duerme, y al tocarlo despierta un momento.

    // La pista se ve unos segundos al entrar y luego desaparece.
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(5000); showHint = false }
    val hintAlpha by animateFloatAsState(if (showHint) 1f else 0f, tween(800), label = "hint")

    // Para que la pantalla OLED no se "queme", la cara se desplaza un poco cada minuto.
    var drift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            drift = Random.nextInt(-24, 25) to Random.nextInt(-40, 41)
        }
    }
    val driftX by animateFloatAsState(drift.first.toFloat(), tween(4000), label = "dx")
    val driftY by animateFloatAsState(drift.second.toFloat(), tween(4000), label = "dy")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { MoodEngine.onNightTap() }, onLongPress = { onExit() })
            }
    ) {
        LiveOrbiFace(Modifier.fillMaxSize().offset(driftX.dp, driftY.dp))
        Text(
            "Toca para despertar a Orbi · Mantén presionado para salir",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(hintAlpha),
        )
    }
}
