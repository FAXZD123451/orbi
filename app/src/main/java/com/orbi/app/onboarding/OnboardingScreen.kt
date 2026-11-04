package com.orbi.app.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbi.app.brain.MoodEngine
import com.orbi.app.face.LiveOrbiFace

/**
 * Un paso de la bienvenida.
 * @param isDone si el permiso ya está dado (se vuelve a revisar cada vez que regresas a la app).
 * @param action lo que hace el botón principal (pedir el permiso o abrir Ajustes); null = solo texto.
 */
class OnboardingStep(
    val title: String,
    val body: String,
    val actionLabel: String? = null,
    val optional: Boolean = false,
    val isDone: () -> Boolean = { false },
    val action: (() -> Unit)? = null,
)

/**
 * Bienvenida: Orbi se presenta y pide los permisos de uno en uno, explicando para qué sirven.
 * @param tick cambia cada vez que la app vuelve al frente, para revisar de nuevo los permisos.
 */
@Composable
fun OnboardingScreen(steps: List<OnboardingStep>, tick: Int, onFinish: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val dim = Color.White.copy(alpha = 0.6f)

    fun next() {
        MoodEngine.cheer()
        if (index < steps.lastIndex) index++ else onFinish()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Puntos de progreso.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            steps.indices.forEach { i ->
                Box(
                    Modifier
                        .size(if (i == index) 22.dp else 8.dp, 8.dp)
                        .clip(CircleShape)
                        .background(if (i <= index) Color.White else Color.White.copy(alpha = 0.2f))
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .width(220.dp)
                .aspectRatio(1.15f)
                .clip(RoundedCornerShape(36.dp))
                .background(Color.Black)
        ) {
            LiveOrbiFace(Modifier.fillMaxSize(), zoom = 1.2f)
        }
        Spacer(Modifier.height(32.dp))

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            },
            label = "step",
            modifier = Modifier.weight(1f),
        ) { i ->
            val step = steps[i]
            // `tick` se lee aquí para que el estado del permiso se recalcule al volver de Ajustes.
            val done = tick >= 0 && step.isDone()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(step.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(step.body, color = dim, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                if (done && step.action != null) {
                    Spacer(Modifier.height(16.dp))
                    Text("✓ Listo", color = Color(0xFF3DD68C), fontWeight = FontWeight.Medium)
                }
            }
        }

        val step = steps[index]
        val done = tick >= 0 && step.isDone()
        Button(
            onClick = { if (step.action != null && !done) step.action.invoke() else next() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when {
                    index == steps.lastIndex -> "¡Empezar!"
                    step.action == null || done -> "Siguiente"
                    else -> step.actionLabel ?: "Permitir"
                },
                fontSize = 16.sp,
            )
        }
        // Siempre se puede seguir sin dar el permiso: Orbi funciona con lo que tenga.
        if (step.action != null && !done) {
            TextButton(onClick = ::next) {
                Text(if (step.optional) "Ahora no" else "Saltar por ahora", color = dim)
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}
