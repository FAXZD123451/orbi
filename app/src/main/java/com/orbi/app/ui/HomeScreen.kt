package com.orbi.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbi.app.AssistantState
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine
import com.orbi.app.face.LiveOrbiFace
import kotlinx.coroutines.delay

/** Colores de la app: negro puro y blancos con distinta intensidad, como la cara de Orbi. */
object Palette {
    val Background = Color.Black
    val Surface = Color(0xFF111113)
    val Raised = Color(0xFF1C1C1F)
    val Line = Color(0x1FFFFFFF)
    val Text = Color.White
    val Dim = Color(0x8CFFFFFF)
    val Faint = Color(0x52FFFFFF)
    val Ok = Color(0xFF3DD68C)
    val Warn = Color(0xFFFFB224)
}

/** Todo lo que la pantalla principal puede pedirle a la Activity. */
class HomeActions(
    val onTalk: () -> Unit,
    val onNightMode: () -> Unit,
    val onToggleBubble: () -> Unit,
    val settings: SettingsActions,
)

/**
 * Pantalla principal: Orbi grande sobre negro, una línea de texto con lo que está pasando,
 * el botón para hablarle y dos íconos (modo noche y ajustes). Nada más.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(actions: HomeActions) {
    var showSettings by remember { mutableStateOf(false) }
    val bubbleVisible by OrbiState.bubbleVisible.collectAsState()

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Barra superior: luna · nombre · ajustes ----
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(onClick = actions.onNightMode) { MoonIcon(Palette.Dim, Palette.Raised) }
                Text(
                    "ORBI", color = Palette.Dim, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 6.sp,
                )
                CircleIconButton(onClick = { showSettings = true }) { TuneIcon(Palette.Dim, Palette.Raised) }
            }

            Spacer(Modifier.weight(1f))

            // ---- Orbi: tócalo para que reaccione, frota el dedo encima para acariciarlo ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(Unit) { detectTapGestures(onTap = { MoodEngine.onTap() }) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { MoodEngine.onPetStart() },
                            onDrag = { change, _ -> change.consume(); MoodEngine.onPetting() },
                            onDragEnd = { MoodEngine.onPetEnd() },
                            onDragCancel = { MoodEngine.onPetEnd() },
                        )
                    }
            ) {
                LiveOrbiFace(Modifier.fillMaxSize(), zoom = 1.1f)
            }

            Caption(Modifier.fillMaxWidth().height(120.dp))

            Spacer(Modifier.weight(1f))

            MicButton(onClick = actions.onTalk)

            Spacer(Modifier.height(18.dp))
            // Discreto: Orbi sobre tus otras apps (isla o burbuja).
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = actions.onToggleBubble)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (bubbleVisible) Palette.Ok else Palette.Faint))
                Text(
                    if (bubbleVisible) "  Orbi te acompaña en tus apps" else "  Llevar a Orbi a tus apps",
                    color = Palette.Dim, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
            dragHandle = {
                Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(Palette.Line))
            },
        ) {
            SettingsContent(actions.settings)
        }
    }
}

/**
 * La línea de texto bajo Orbi: qué está pasando en la conversación. Mientras habla muestra lo que
 * dice (como subtítulos) y después lo deja unos segundos antes de volver a la pista "Di «Oye Orbi»".
 */
@Composable
private fun Caption(modifier: Modifier) {
    val state by OrbiState.assistant.collectAsState()
    val heard by OrbiState.lastHeard.collectAsState()
    val reply by OrbiState.lastReply.collectAsState()
    val voice by OrbiState.voiceEnabled.collectAsState()
    val earReady by OrbiState.earReady.collectAsState()
    var keepReply by remember { mutableStateOf(false) }

    // Tras responder, el subtítulo se queda 8 s.
    LaunchedEffect(state) {
        if (state == AssistantState.SPEAKING) keepReply = true
        if (state == AssistantState.IDLE && keepReply) { delay(8000); keepReply = false }
    }

    val content: Pair<String, String> = when {
        state == AssistantState.LISTENING -> "" to "Te escucho…"
        state == AssistantState.THINKING -> heard to "Pensando…"
        (state == AssistantState.SPEAKING || keepReply) && reply.isNotBlank() -> heard to reply
        voice && earReady -> "" to "Di «Oye Orbi»"
        else -> "" to "Toca el micrófono para hablar"
    }

    AnimatedContent(
        targetState = content,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
        label = "caption",
        modifier = modifier,
    ) { (you, orbi) ->
        Column(
            Modifier.fillMaxSize().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (you.isNotBlank()) {
                Text(
                    "“$you”", color = Palette.Faint, fontSize = 14.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            val isAnswer = orbi == reply && reply.isNotBlank()
            Text(
                orbi,
                color = if (isAnswer) Palette.Text else Palette.Dim,
                fontSize = if (isAnswer) 19.sp else 16.sp,
                lineHeight = if (isAnswer) 26.sp else 22.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Botón redondo del micrófono. Mientras escucha, late con un anillo alrededor. */
@Composable
private fun MicButton(onClick: () -> Unit) {
    val state by OrbiState.assistant.collectAsState()
    val active = state != AssistantState.IDLE
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    val press by animateFloatAsState(if (active) 0.92f else 1f, tween(200), label = "press")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        if (state == AssistantState.LISTENING) {
            Box(Modifier.size(84.dp).scale(ring).clip(CircleShape).border(1.5.dp, Palette.Dim, CircleShape))
        }
        Box(
            Modifier
                .size(84.dp)
                .scale(press)
                .clip(CircleShape)
                .background(if (active) Palette.Raised else Color.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !active,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            MicIcon(if (active) Color.White else Color.Black)
        }
    }
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Palette.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
