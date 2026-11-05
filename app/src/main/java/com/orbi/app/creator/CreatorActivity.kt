package com.orbi.app.creator

import android.graphics.PointF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orbi.app.OrbiState
import com.orbi.app.assistant.Assistant
import com.orbi.app.face.LiveOrbiFace
import com.orbi.app.sense.MotionSense
import com.orbi.app.ui.Palette
import com.orbi.app.vision.DebugFrame
import com.orbi.app.vision.FaceTracker
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Modo creador: ves lo mismo que ve Orbi por la cámara frontal, con todo lo que está
 * analizando dibujado encima y sus números en vivo. Es una herramienta para afinar umbrales;
 * nada de lo que se muestra aquí se guarda.
 */
class CreatorActivity : ComponentActivity() {
    private var previewView: PreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CreatorScreen(
                onPreviewReady = { view ->
                    previewView = view
                    attachCamera()
                },
                onClose = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        OrbiState.creatorMode.value = true
        OrbiState.appVisible.value = true // solo un Orbi: la isla se esconde
        Assistant.attach(this)
        attachCamera()
    }

    override fun onPause() {
        OrbiState.creatorMode.value = false
        OrbiState.appVisible.value = false
        OrbiState.debugFrame.value = null
        FaceTracker.previewProvider = null
        FaceTracker.release(this, this)
        Assistant.detach()
        super.onPause()
    }

    private fun attachCamera() {
        val view = previewView ?: return
        if (!OrbiState.creatorMode.value) return
        FaceTracker.previewProvider = view.surfaceProvider
        FaceTracker.acquire(this, this)
    }
}

@Composable
private fun CreatorScreen(onPreviewReady: (PreviewView) -> Unit, onClose: () -> Unit) {
    val frame by OrbiState.debugFrame.collectAsState()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Cámara + lo que ve Orbi ----
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onPreviewReady(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) { frame?.let { drawAnalysis(it) } }

            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("MODO CREADOR", color = Color.White, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(0xAA000000)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 16.sp) }
            }
        }

        // ---- Panel de datos ----
        DataPanel(frame)
    }
}

/**
 * Dibuja el análisis encima de la vista previa. La vista llena el espacio recortando (FILL_CENTER)
 * y la cámara frontal se ve como espejo, así que se aplica la misma escala, recorte y reflejo.
 */
private fun DrawScope.drawAnalysis(f: DebugFrame) {
    val scale = max(size.width / f.imageW, size.height / f.imageH)
    val dx = (size.width - f.imageW * scale) / 2
    val dy = (size.height - f.imageH * scale) / 2
    fun map(x: Float, y: Float) = Offset(size.width - (x * scale + dx), y * scale + dy)
    fun map(p: PointF) = map(p.x, p.y)
    val stroke = 2.dp.toPx()

    fun polyline(points: List<PointF>, color: Color, closed: Boolean) {
        if (points.size < 2) return
        val path = Path().apply {
            val first = map(points.first())
            moveTo(first.x, first.y)
            points.drop(1).forEach { val p = map(it); lineTo(p.x, p.y) }
            if (closed) close()
        }
        drawPath(path, color, style = Stroke(stroke))
    }

    f.box?.let { b ->
        val a = map(b.right, b.top) // espejo: el borde derecho de la imagen queda a la izquierda
        val c = map(b.left, b.bottom)
        drawRect(Color.White, a, Size(c.x - a.x, c.y - a.y), style = Stroke(stroke))
    }
    polyline(f.faceOval, Color.White.copy(alpha = 0.45f), closed = true)
    f.eyes.forEach { polyline(it, Color(0xFF7FD8FF), closed = true) }
    f.lips.forEach { polyline(it, Palette.Ok, closed = false) }
}

@Composable
private fun DataPanel(f: DebugFrame?) {
    val expression by OrbiState.expression.collectAsState()
    val assistant by OrbiState.assistant.collectAsState()
    val look by OrbiState.look.collectAsState()
    val faceVisible by OrbiState.faceVisible.collectAsState()
    val searching by OrbiState.searching.collectAsState()
    val smiling by OrbiState.smiling.collectAsState()
    val mouthOpen by OrbiState.mouthOpen.collectAsState()
    val eyesClosed by OrbiState.userEyesClosed.collectAsState()
    val gravity by OrbiState.gravity.collectAsState()
    val faceDown by OrbiState.faceDown.collectAsState()
    val roomDark by OrbiState.roomDark.collectAsState()
    val hearing by OrbiState.earHearing.collectAsState()
    val heard by OrbiState.lastHeard.collectAsState()

    // Los sensores no son flujos: se leen 5 veces por segundo.
    var lux by remember { mutableFloatStateOf(0f) }
    var gz by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            lux = MotionSense.currentLux
            gz = MotionSense.gravityZ
            delay(200)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(Palette.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                LiveOrbiFace(Modifier.fillMaxSize(), zoom = 1.3f)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(expression.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text("asistente: ${assistant.name.lowercase()}", color = Palette.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    when { faceVisible -> "te ve"; searching -> "buscando…"; else -> "sin cara" } +
                        "  ·  mirada ${"%+.2f".format(look.x)}, ${"%+.2f".format(look.y)}",
                    color = Palette.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        Group("Cámara") {
            Metric("cuadros/s", f?.let { "%.1f".format(it.fps) } ?: "–")
            Metric("análisis", f?.let { "${it.analysisMs} ms" } ?: "–")
            Metric("luz imagen", f?.let { "${it.light} / 255" } ?: "–", warn = (f?.light ?: 255) < 22, note = "tapada < 22")
            Metric("caras", f?.faces?.toString() ?: "–")
        }
        Group("Cara (crudo → filtrado)") {
            Metric("sonrisa", prob(f?.smile), on = smiling, note = "> 0,70 · sale < 0,35")
            Metric("ojo izq / der", "${prob(f?.leftEye)} / ${prob(f?.rightEye)}", on = eyesClosed, note = "cerrados < 0,20")
            Metric("boca", f?.mouthRatio?.let { "%.3f".format(it) } ?: "–", on = mouthOpen, note = "abre > 0,07")
            Metric("tamaño cara", f?.widthRatio?.let { "%.2f".format(it) } ?: "–", note = "cerca > 0,55")
            Metric("cabeza X/Y/Z", f?.let { "%+.0f° %+.0f° %+.0f°".format(it.headX, it.headY, it.headZ) } ?: "–")
        }
        Group("Oído") {
            Metric("escuchando", hearing.ifBlank { "…" })
            Metric("última orden", heard.ifBlank { "–" })
        }
        Group("Sensores") {
            Metric("inclinación", "%+.2f".format(gravity.x))
            Metric("gravedad Z", "%+.1f m/s²".format(gz), on = faceDown, note = "boca abajo < −8")
            Metric("luz ambiente", "%.0f lux".format(lux), on = roomDark, note = "oscuro < 4")
        }
    }
}

private fun prob(v: Float?) = v?.let { "%.2f".format(it) } ?: "–"

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), color = Palette.Faint, fontSize = 10.sp, letterSpacing = 2.sp)
        content()
    }
}

/** Una fila: nombre, valor y, si aplica, un punto que se enciende cuando la detección "cuenta". */
@Composable
private fun Metric(label: String, value: String, on: Boolean? = null, warn: Boolean = false, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(118.dp))
        Text(
            value, color = if (warn) Palette.Warn else Color.White, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f),
        )
        if (note != null) Text(note, color = Palette.Faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        if (on != null) {
            Box(
                Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape)
                    .background(if (on) Palette.Ok else Palette.Line)
            )
        }
    }
}
