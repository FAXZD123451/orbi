package com.orbi.app.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.orbi.app.Cue
import com.orbi.app.CueType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Las expresiones de Orbi. Las elige [com.orbi.app.brain.MoodEngine], no el usuario. */
enum class Expression {
    NEUTRAL,
    HAPPY,
    SAD,
    ANGRY,
    SURPRISED,
    TALKING,
    SLEEPING,
    /** Ojos en espiral: lo sacudiste o lo lanzaste. */
    DIZZY,
    /** Ojitos cerrados de gusto: lo estás acariciando. */
    PURR,
    /** Cierra un solo ojo (el lado lo indica winkSide). */
    WINK,
    /** Ojos pequeños y temblando: le tapaste la cámara. */
    SCARED,
    /** Ojos a medio cerrar: el cuarto está a oscuras o le está dando sueño. */
    DROWSY,
}

/*
 * Cada expresión se describe con unos pocos números (en fracciones del tamaño de la cara).
 * Al cambiar de expresión, cada número se anima por separado, así Orbi "transforma"
 * los ojos en vez de saltar de un dibujo a otro.
 *
 * Truco de dibujo: el fondo es negro, así que para dar forma a un ojo (cejas enojadas,
 * ojos felices en media luna) se dibuja el ojo completo y luego se "borra" con negro.
 */
private data class EyeShape(
    val w: Float,
    val h: Float,
    val radius: Float,
    /** >0: se corta la esquina interior de arriba (enojado). <0: la exterior (triste). */
    val tilt: Float = 0f,
    /** Fracción del ojo que se tapa desde abajo (feliz = media luna). */
    val cutBottom: Float = 0f,
    /** Fracción del ojo que se tapa desde arriba (párpado caído = sueño). */
    val cutTop: Float = 0f,
)

private data class MouthShape(
    val w: Float,
    /** >0 sonrisa, <0 tristeza. */
    val curve: Float = 0f,
    /** Alto del óvalo cuando la boca está abierta (0 = cerrada). */
    val open: Float = 0f,
)

private val NEUTRAL_EYE = EyeShape(w = 0.13f, h = 0.26f, radius = 0.055f)

private fun eyeFor(e: Expression) = when (e) {
    Expression.NEUTRAL, Expression.TALKING, Expression.WINK -> NEUTRAL_EYE
    Expression.HAPPY -> EyeShape(w = 0.18f, h = 0.18f, radius = 0.09f, cutBottom = 0.5f)
    Expression.PURR -> EyeShape(w = 0.17f, h = 0.17f, radius = 0.085f, cutBottom = 0.74f)
    Expression.SAD -> EyeShape(w = 0.13f, h = 0.22f, radius = 0.05f, tilt = -0.38f)
    Expression.ANGRY -> EyeShape(w = 0.14f, h = 0.22f, radius = 0.05f, tilt = 0.42f)
    Expression.SURPRISED -> EyeShape(w = 0.17f, h = 0.17f, radius = 0.085f)
    Expression.SCARED -> EyeShape(w = 0.09f, h = 0.12f, radius = 0.045f)
    Expression.DIZZY -> EyeShape(w = 0.14f, h = 0.14f, radius = 0.07f)
    Expression.SLEEPING -> EyeShape(w = 0.15f, h = 0.028f, radius = 0.014f)
    Expression.DROWSY -> EyeShape(w = 0.14f, h = 0.2f, radius = 0.05f, cutTop = 0.55f)
}

private fun mouthFor(e: Expression) = when (e) {
    Expression.NEUTRAL -> MouthShape(w = 0.10f)
    Expression.HAPPY -> MouthShape(w = 0.16f, curve = 0.045f)
    Expression.PURR -> MouthShape(w = 0.11f, curve = 0.035f)
    Expression.WINK -> MouthShape(w = 0.13f, curve = 0.04f)
    Expression.SAD -> MouthShape(w = 0.12f, curve = -0.035f)
    Expression.ANGRY -> MouthShape(w = 0.11f, curve = -0.01f)
    Expression.SURPRISED -> MouthShape(w = 0.065f, open = 0.075f)
    Expression.SCARED -> MouthShape(w = 0.05f, open = 0.035f)
    Expression.TALKING -> MouthShape(w = 0.11f, open = 0.06f)
    Expression.DIZZY -> MouthShape(w = 0.12f)
    Expression.SLEEPING -> MouthShape(w = 0.05f)
    Expression.DROWSY -> MouthShape(w = 0.06f, curve = -0.005f)
}

private val NO_BLINK = setOf(
    Expression.SLEEPING, Expression.HAPPY, Expression.PURR, Expression.DIZZY, Expression.WINK,
)

/**
 * La cara de Orbi: ojos y boca blancos sobre negro.
 *
 * @param look hacia dónde mira (-1..1 en cada eje).
 * @param idleGlances si mira de reojo de vez en cuando para parecer vivo.
 * @param zoom agranda ojos y boca respecto al marco (en la burbuja conviene > 1).
 * @param headTilt inclinación de la cara en grados (imita la de tu cabeza).
 * @param winkSide qué ojo cierra en el guiño: -1 izquierdo, 1 derecho.
 * @param cue movimiento de cabeza que se dispara una vez (asentir o negar).
 * @param searching te está buscando con la cámara: barre la mirada de lado a lado, concentrado.
 */
@Composable
fun OrbiFace(
    expression: Expression,
    modifier: Modifier = Modifier,
    look: Offset = Offset.Zero,
    idleGlances: Boolean = true,
    zoom: Float = 1f,
    headTilt: Float = 0f,
    winkSide: Int = 1,
    cue: Cue? = null,
    searching: Boolean = false,
    color: Color = Color.White,
) {
    val eye = eyeFor(expression)
    val mouth = mouthFor(expression)
    val spec: AnimationSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 320f)

    val eyeW by animateFloatAsState(eye.w, spec, label = "eyeW")
    val eyeH by animateFloatAsState(eye.h, spec, label = "eyeH")
    val eyeR by animateFloatAsState(eye.radius, spec, label = "eyeR")
    val tilt by animateFloatAsState(eye.tilt, spec, label = "tilt")
    val cut by animateFloatAsState(eye.cutBottom, spec, label = "cut")
    // El párpado baja lento (sueño) y sube más rápido. Buscando, entrecierra un poco (concentrado).
    val isSearching = searching && expression == Expression.NEUTRAL
    val lidTarget = maxOf(eye.cutTop, if (isSearching) 0.18f else 0f)
    val cutTop by animateFloatAsState(lidTarget, tween(if (lidTarget > 0f) 700 else 250), label = "cutTop")
    val mouthW by animateFloatAsState(mouth.w, spec, label = "mouthW")
    val curve by animateFloatAsState(mouth.curve, spec, label = "curve")
    val open by animateFloatAsState(mouth.open, spec, label = "open")
    val zzz by animateFloatAsState(if (expression == Expression.SLEEPING) 1f else 0f, tween(600), label = "zzz")
    val dizzy by animateFloatAsState(if (expression == Expression.DIZZY) 1f else 0f, tween(250), label = "dizzy")
    val scared by animateFloatAsState(if (expression == Expression.SCARED) 1f else 0f, tween(200), label = "scared")
    val tiltDeg by animateFloatAsState(headTilt, spring(dampingRatio = 0.8f, stiffness = 120f), label = "head")
    val winkL by animateFloatAsState(
        if (expression == Expression.WINK && winkSide < 0) 0.1f else 1f, tween(110), label = "winkL")
    val winkR by animateFloatAsState(
        if (expression == Expression.WINK && winkSide > 0) 0.1f else 1f, tween(110), label = "winkR")

    // ---- Transiciones con personalidad: todo el cuerpo reacciona al cambiar de expresión ----
    val hopY = remember { Animatable(0f) }     // desplazamiento vertical (fracción de u)
    val jitterX = remember { Animatable(0f) }  // temblor horizontal
    val pop = remember { Animatable(1f) }      // escala
    LaunchedEffect(expression) {
        when (expression) {
            Expression.HAPPY, Expression.WINK -> {          // saltito de alegría
                hopY.animateTo(-0.04f, tween(110))
                hopY.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 500f))
            }
            Expression.ANGRY -> repeat(3) {                  // tiembla de rabia
                jitterX.animateTo(0.018f, tween(35))
                jitterX.animateTo(-0.018f, tween(35))
            }.also { jitterX.animateTo(0f, tween(35)) }
            Expression.SURPRISED -> {                        // "¡pop!"
                pop.animateTo(1.1f, tween(90))
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
            }
            else -> Unit
        }
    }
    // Se encoge de miedo y se "desinfla" de tristeza mientras dure.
    val shrink by animateFloatAsState(if (expression == Expression.SCARED) 0.88f else 1f, spring(0.6f, 300f), label = "shrink")
    val droop by animateFloatAsState(
        when (expression) { Expression.SAD -> 0.03f; Expression.DROWSY -> 0.02f; else -> 0f }, tween(500), label = "droop")

    // Asentir / negar con la cabeza (cuando tú lo haces).
    val nodY = remember { Animatable(0f) }
    val headShakeX = remember { Animatable(0f) }
    LaunchedEffect(cue?.id) {
        when (cue?.type) {
            CueType.NOD -> repeat(2) {
                nodY.animateTo(0.05f, tween(140))
                nodY.animateTo(-0.01f, tween(140))
            }.also { nodY.animateTo(0f, tween(120)) }
            CueType.HEAD_SHAKE -> repeat(2) {
                headShakeX.animateTo(0.05f, tween(130))
                headShakeX.animateTo(-0.05f, tween(130))
            }.also { headShakeX.animateTo(0f, tween(110)) }
            null -> Unit
        }
    }

    // Parpadeo a intervalos irregulares.
    val blink = remember { Animatable(1f) }
    LaunchedEffect(expression) {
        blink.snapTo(1f)
        if (expression in NO_BLINK) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2500, 5500))
            blink.animateTo(0.08f, tween(70))
            blink.animateTo(1f, tween(90))
        }
    }

    // Miradas de reojo cuando está tranquilo (o nervioso, si está asustado).
    val glance = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(expression, idleGlances, isSearching) {
        if (isSearching) {
            // Búsqueda: barre de un lado al otro, como un radar, y a veces se asoma arriba o abajo.
            var sweep = 0
            while (true) {
                val side = if (sweep % 2 == 0) 1f else -1f
                val peek = when (sweep % 5) { 2 -> -0.8f; 4 -> 0.6f; else -> -0.15f }
                glance.animateTo(Offset(side, peek), tween(850, easing = FastOutSlowInEasing))
                delay(Random.nextLong(250, 600))
                sweep++
            }
        }
        if (!idleGlances || expression == Expression.SLEEPING || expression == Expression.DIZZY) {
            glance.animateTo(Offset.Zero, tween(300))
            return@LaunchedEffect
        }
        val nervous = expression == Expression.SCARED
        while (true) {
            delay(if (nervous) Random.nextLong(300, 700) else Random.nextLong(3000, 7000))
            val target = Offset(Random.nextFloat() * 2 - 1, (Random.nextFloat() * 2 - 1) * 0.5f)
            glance.animateTo(target, tween(if (nervous) 120 else 220))
            delay(if (nervous) Random.nextLong(200, 500) else Random.nextLong(600, 1500))
            if (!nervous) glance.animateTo(Offset.Zero, tween(260))
        }
    }

    val loop = rememberInfiniteTransition(label = "loop")
    val talk by loop.animateFloat(0.25f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "talk")
    val breath by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breath")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val tremble by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(55), RepeatMode.Reverse), label = "tremble")
    val wobble by loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "wobble")

    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        drawRect(Color.Black)
        val base = size.minDimension
        val u = base * zoom
        val sleeping = expression == Expression.SLEEPING
        val float = if (sleeping) breath * 0.008f * u else 0f
        val shakeX = tremble * scared * 0.006f * u
        val lookOffset = (look + glance.value) * (0.045f * u) + Offset(shakeX, 0f)
        // Mareado: toda la cara se balancea un poco.
        val sway = wobble * dizzy * 8f
        val bodyOffset = Offset((jitterX.value + headShakeX.value) * u, (hopY.value + nodY.value + droop) * u)
        val bodyScale = pop.value * shrink

        withTransform({
            translate(bodyOffset.x, bodyOffset.y)
            scale(bodyScale, bodyScale, center)
            rotate(tiltDeg + sway, center)
        }) {
            val eyeY = center.y - 0.05f * u + float
            val dx = 0.17f * u
            val eyeColor = color.copy(alpha = color.alpha * (1f - dizzy))

            for (side in intArrayOf(-1, 1)) {
                val c = Offset(center.x + side * dx, eyeY) + lookOffset
                val wink = if (side < 0) winkL else winkR
                if (dizzy < 0.99f) {
                    drawEye(
                        c = c, side = side,
                        w = eyeW * u, h = eyeH * u * blink.value * wink, r = eyeR * u,
                        tilt = tilt, cut = cut, cutTop = cutTop, pad = 0.03f * u, color = eyeColor,
                    )
                }
                if (dizzy > 0.01f) {
                    drawSpiral(c, 0.075f * u, spin * side, 0.022f * u, color.copy(alpha = dizzy))
                }
            }

            val mouthC = Offset(center.x, center.y + 0.2f * u + float) + lookOffset * 0.5f
            if (dizzy > 0.5f) {
                drawWavyMouth(mouthC, 0.13f * u, 0.014f * u, spin, 0.026f * u, color)
            } else {
                val talkOpen = if (expression == Expression.TALKING) talk else 1f
                drawMouth(mouthC, mouthW * u, curve * u, open * u * talkOpen, 0.028f * u, color)
            }
        }

        if (zzz > 0.01f) {
            // Las "z" usan el tamaño del marco (no el zoom) para no salirse de la burbuja.
            val style = TextStyle(color = color.copy(alpha = zzz * 0.8f), fontWeight = FontWeight.Bold)
            drawText(textMeasurer, "z", Offset(center.x + 0.3f * base, center.y - 0.36f * base - float * 2),
                style.copy(fontSize = (0.08f * base).toSp()))
            drawText(textMeasurer, "z", Offset(center.x + 0.38f * base, center.y - 0.46f * base - float * 3),
                style.copy(fontSize = (0.06f * base).toSp()))
        }
    }
}

private fun DrawScope.drawEye(
    c: Offset, side: Int, w: Float, h: Float, r: Float, tilt: Float, cut: Float, cutTop: Float, pad: Float, color: Color,
) {
    val top = c.y - h / 2
    val left = c.x - w / 2
    val radius = min(r, min(w, h) / 2)
    drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(radius))

    if (abs(tilt) > 0.001f) {
        // La esquina "interior" es la que mira hacia el centro de la cara.
        val innerX = c.x - side * w / 2
        val outerX = c.x + side * w / 2
        val lowX = if (tilt > 0) innerX else outerX
        val highX = if (tilt > 0) outerX else innerX
        val outLow = if (lowX > highX) pad else -pad
        val outHigh = -outLow
        val depth = abs(tilt) * h
        val path = Path().apply {
            moveTo(highX + outHigh, top - pad)
            lineTo(lowX + outLow, top - pad)
            lineTo(lowX + outLow, top + depth)
            lineTo(highX + outHigh, top - pad * 0.2f)
            close()
        }
        drawPath(path, Color.Black)
    }

    if (cut > 0.001f) {
        drawRect(Color.Black, Offset(left - pad, c.y + h / 2 - cut * h), Size(w + 2 * pad, cut * h + pad))
    }
    if (cutTop > 0.001f) {
        // Párpado: un borde recto que tapa la parte de arriba del ojo.
        drawRect(Color.Black, Offset(left - pad, top - pad), Size(w + 2 * pad, cutTop * h + pad))
    }
}

private fun DrawScope.drawMouth(c: Offset, w: Float, curve: Float, open: Float, stroke: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x - w / 2, c.y)
        quadraticTo(c.x, c.y + curve * 2, c.x + w / 2, c.y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    if (open > 0.5f) {
        drawOval(color, Offset(c.x - w / 2, c.y - open / 2), Size(w, open))
    }
}

/** Espiral que gira: los ojos de Orbi cuando está mareado. */
private fun DrawScope.drawSpiral(c: Offset, radius: Float, angleDeg: Float, stroke: Float, color: Color) {
    val turns = 2.3f
    val steps = 70
    val start = angleDeg / 180f * PI.toFloat()
    val path = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = start + t * turns * 2 * PI.toFloat()
        val p = Offset(c.x + cos(a) * radius * t, c.y + sin(a) * radius * t)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/** Boca ondulada (mareado). */
private fun DrawScope.drawWavyMouth(c: Offset, w: Float, amp: Float, phaseDeg: Float, stroke: Float, color: Color) {
    val steps = 30
    val phase = phaseDeg / 180f * PI.toFloat()
    val path = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = c.x - w / 2 + t * w
        val y = c.y + sin(t * 4 * PI.toFloat() + phase) * amp
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}
