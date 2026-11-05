package com.orbi.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Íconos dibujados a mano con líneas finas, para que combinen con el estilo de Orbi
 * (y sin depender de librerías de íconos).
 */

/** Luna creciente (modo noche). [background] es el color de detrás, para "recortar" la luna. */
@Composable
fun MoonIcon(color: Color, background: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.42f
        drawCircle(color, r, center)
        drawCircle(background, r * 0.9f, center + Offset(r * 0.55f, -r * 0.35f))
    }
}

/** Tres controles deslizantes (ajustes). */
@Composable
fun TuneIcon(color: Color, background: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = 1.8.dp.toPx()
        val knob = 2.6.dp.toPx()
        listOf(0.25f to 0.68f, 0.5f to 0.3f, 0.75f to 0.58f).forEach { (y, x) ->
            val yy = this.size.height * y
            drawLine(color, Offset(w * 0.1f, yy), Offset(w * 0.9f, yy), stroke, StrokeCap.Round)
            drawCircle(background, knob + stroke, Offset(w * x, yy))
            drawCircle(color, knob, Offset(w * x, yy), style = Stroke(stroke))
        }
    }
}

/** Micrófono. */
@Composable
fun MicIcon(color: Color, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 2.2.dp.toPx()
        // Cápsula
        drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), Size(w * 0.28f, h * 0.48f), CornerRadius(w * 0.14f))
        // Soporte en U
        drawArc(
            color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.24f), size = Size(w * 0.52f, h * 0.46f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.88f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.9f), Offset(w * 0.64f, h * 0.9f), stroke, StrokeCap.Round)
    }
}
