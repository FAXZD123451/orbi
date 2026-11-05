package com.orbi.app.face

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.orbi.app.OrbiState

/**
 * La cara de Orbi conectada a todo lo que siente: expresión, cámara, sensores y avisos.
 * Se usa igual en la app, en la burbuja y en el modo noche.
 */
@Composable
fun LiveOrbiFace(modifier: Modifier = Modifier, zoom: Float = 1f) {
    val expression by OrbiState.expression.collectAsState()
    val faceLook by OrbiState.look.collectAsState()
    val faceVisible by OrbiState.faceVisible.collectAsState()
    val gravity by OrbiState.gravity.collectAsState()
    val headTilt by OrbiState.headTilt.collectAsState()
    val winkSide by OrbiState.winkSide.collectAsState()
    val lookHint by OrbiState.lookHint.collectAsState()
    val cue by OrbiState.cue.collectAsState()
    val searching by OrbiState.searching.collectAsState()
    val tracking by OrbiState.trackingEnabled.collectAsState()

    // Prioridad de la mirada: un aviso (notificación) > tu cara > la gravedad del celular.
    val look = when {
        expression == Expression.SLEEPING -> Offset.Zero
        lookHint != null -> lookHint!!
        faceVisible -> faceLook
        else -> gravity
    }
    OrbiFace(
        expression = expression,
        modifier = modifier,
        look = look,
        idleGlances = !faceVisible && lookHint == null && gravity.getDistance() < 0.2f,
        zoom = zoom,
        headTilt = if (faceVisible) headTilt else 0f,
        winkSide = winkSide,
        cue = cue,
        searching = tracking && searching && !faceVisible && lookHint == null,
    )
}
