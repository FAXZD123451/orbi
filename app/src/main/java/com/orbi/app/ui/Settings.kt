package com.orbi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbi.app.BubbleMode
import com.orbi.app.OrbiState
import com.orbi.app.Prefs
import kotlin.math.roundToInt

/** Lo que el panel de ajustes puede pedirle a la Activity. */
class SettingsActions(
    val onToggleVoice: (Boolean) -> Unit,
    val onToggleTracking: (Boolean) -> Unit,
    val onNotificationAccess: () -> Unit,
    val onInstallBrain: () -> Unit,
    val onShowWelcome: () -> Unit,
)

/** Todas las opciones, agrupadas, en el panel que sube desde abajo. */
@Composable
fun SettingsContent(actions: SettingsActions) {
    val voice by OrbiState.voiceEnabled.collectAsState()
    val earReady by OrbiState.earReady.collectAsState()
    val brain by OrbiState.brainInstalled.collectAsState()
    val copying by OrbiState.brainCopyProgress.collectAsState()
    val tracking by OrbiState.trackingEnabled.collectAsState()
    val cameraAllowed by OrbiState.cameraAllowed.collectAsState()
    val faceVisible by OrbiState.faceVisible.collectAsState()
    val sound by OrbiState.soundEnabled.collectAsState()
    val notifications by OrbiState.notificationsAllowed.collectAsState()
    val mode by OrbiState.bubbleMode.collectAsState()
    val floatSize by OrbiState.floatSizeDp.collectAsState()
    val barHeight by OrbiState.barHeightDp.collectAsState()
    val hands by OrbiState.handsEnabled.collectAsState()
    val onlineSpeech by OrbiState.onlineSpeech.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ajustes", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))

        // ---------- Asistente ----------
        Section("Asistente") {
            StatusRow(earReady, "Oído", if (earReady) "Listo, sin internet" else "Falta el modelo de voz o el micrófono")
            StatusRow(brain, "Cerebro", if (brain) "Gemma, dentro de tu celular" else "Falta instalar Gemma")
            copying?.let { p ->
                Text("Instalando cerebro… ${(p * 100).roundToInt()} %", color = Palette.Dim, fontSize = 13.sp)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Color.White,
                    trackColor = Palette.Line)
            }
            if (!brain && copying == null) {
                Pill("Instalar cerebro (archivo .task)", actions.onInstallBrain)
            }
            Divider()
            ToggleRow("Responder a «Oye Orbi»", "Escucha solo para detectar su nombre; no graba nada.", voice, actions.onToggleVoice)
            Divider()
            ToggleRow(
                "Reconocimiento de Google en línea",
                "Si tu celular no entiende la voz por sí solo, usa Google (más preciso; el audio de tu pregunta va a Google).",
                onlineSpeech,
            ) { OrbiState.onlineSpeech.value = it }
        }

        // ---------- Presencia ----------
        Section("Presencia") {
            ToggleRow(
                "Seguir con la mirada",
                when {
                    !cameraAllowed -> "Necesita permiso de cámara."
                    faceVisible -> "Te está viendo. Todo se procesa en tu celular."
                    else -> "Usa la cámara frontal; no guarda ni envía imágenes."
                },
                tracking, actions.onToggleTracking,
            )
            Divider()
            ToggleRow("Ver tus manos", "Reacciona a 👍 👎 ✋ ✊ ✌️ ☝️ 🤟. Se procesa en tu celular.", hands) {
                OrbiState.handsEnabled.value = it
            }
            Divider()
            ToggleRow("Sonidos", "Bips y chirridos cuando reacciona.", sound) { OrbiState.soundEnabled.value = it }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reaccionar a notificaciones", color = Palette.Text, fontSize = 15.sp)
                    Text(
                        if (notifications) "Activado. Solo sabe que llegó algo." else "Se sorprende al llegar un mensaje.",
                        color = Palette.Dim, fontSize = 13.sp,
                    )
                }
                if (!notifications) Pill("Activar", actions.onNotificationAccess)
            }
            Divider()
            Text("Forma en tus apps", color = Palette.Text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment("Isla superior", mode == BubbleMode.TOP_BAR) { OrbiState.bubbleMode.value = BubbleMode.TOP_BAR }
                Segment("Burbuja", mode == BubbleMode.FLOATING) { OrbiState.bubbleMode.value = BubbleMode.FLOATING }
            }
            val isBar = mode == BubbleMode.TOP_BAR
            Text(
                if (isBar) "Alto al expandirse: ${barHeight.roundToInt()} dp" else "Tamaño: ${floatSize.roundToInt()} dp",
                color = Palette.Dim, fontSize = 13.sp,
            )
            Slider(
                value = if (isBar) barHeight else floatSize,
                onValueChange = { if (isBar) OrbiState.barHeightDp.value = it else OrbiState.floatSizeDp.value = it },
                valueRange = if (isBar) Prefs.BAR_HEIGHT_MIN..Prefs.BAR_HEIGHT_MAX else Prefs.FLOAT_SIZE_MIN..Prefs.FLOAT_SIZE_MAX,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Palette.Line,
                ),
            )
        }

        // ---------- Más ----------
        Section("Más") {
            Text(
                "Sacúdelo, lánzalo, acarícialo, guíñale un ojo, tápale la cámara, ponlo boca abajo o " +
                    "dale dos golpecitos: siempre reacciona a su manera.",
                color = Palette.Dim, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Divider()
            Text(
                "Ver la bienvenida y los permisos",
                color = Palette.Text, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable(onClick = actions.onShowWelcome).padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), color = Palette.Faint, fontSize = 11.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Raised).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.Text, fontSize = 15.sp)
            Text(subtitle, color = Palette.Dim, fontSize = 13.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                uncheckedThumbColor = Palette.Dim, uncheckedTrackColor = Palette.Surface, uncheckedBorderColor = Palette.Line,
            ),
        )
    }
}

@Composable
private fun StatusRow(ok: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Palette.Ok else Palette.Warn))
        Text(title, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
        Spacer(Modifier.weight(1f))
        Text(detail, color = Palette.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(label, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected) Color.Black else Palette.Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}
