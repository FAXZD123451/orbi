package com.orbi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.orbi.app.brain.MoodEngine
import com.orbi.app.face.LiveOrbiFace
import com.orbi.app.night.NightActivity
import com.orbi.app.overlay.BubbleService
import com.orbi.app.sense.NotificationWatcher
import com.orbi.app.vision.FaceTracker

class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Si acaba de dar la cámara, Orbi empieza a buscarlo ya (aquí y en la burbuja).
            FaceTracker.rebind(this)
            BubbleService.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onToggleBubble = ::toggleBubble,
                    onNightMode = { startActivity(Intent(this, NightActivity::class.java)) },
                    onToggleTracking = ::setTracking,
                    onNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MoodEngine.onAppOpened()
        FaceTracker.acquire(this, this)
        OrbiState.notificationsAllowed.value = NotificationWatcher.isAllowed(this)
    }

    override fun onPause() {
        FaceTracker.release(this, this)
        super.onPause()
    }

    private fun askPermissions() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    private fun setTracking(enabled: Boolean) {
        OrbiState.trackingEnabled.value = enabled
        if (enabled && !FaceTracker.hasPermission(this)) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        FaceTracker.rebind(this)
        BubbleService.refresh(this)
    }

    private fun toggleBubble() {
        if (OrbiState.bubbleVisible.value) {
            BubbleService.stop(this)
            return
        }
        // Para flotar sobre otras apps, Android pide un permiso especial que se da en Ajustes.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa \"Mostrar sobre otras apps\" para Orbi y vuelve", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            return
        }
        BubbleService.start(this)
    }
}

@Composable
private fun HomeScreen(
    onToggleBubble: () -> Unit,
    onNightMode: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onNotificationAccess: () -> Unit,
) {
    val faceVisible by OrbiState.faceVisible.collectAsState()
    val tracking by OrbiState.trackingEnabled.collectAsState()
    val cameraAllowed by OrbiState.cameraAllowed.collectAsState()
    val bubbleVisible by OrbiState.bubbleVisible.collectAsState()
    val sound by OrbiState.soundEnabled.collectAsState()
    val notifications by OrbiState.notificationsAllowed.collectAsState()
    val dim = Color.White.copy(alpha = 0.55f)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Orbi", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Tu asistente de bolsillo", color = dim)

        // Toca a Orbi para que reaccione; desliza el dedo encima para acariciarlo.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black)
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
            LiveOrbiFace(Modifier.fillMaxSize())
        }

        // Qué está "pensando" Orbi, para que se entienda por qué cambia de cara.
        val (dot, status) = when {
            !tracking -> Color(0xFF666666) to "Seguimiento con la cámara apagado"
            !cameraAllowed -> Color(0xFFFFB224) to "Orbi necesita permiso de cámara para verte"
            faceVisible -> Color(0xFF3DD68C) to "Orbi te está viendo"
            else -> Color(0xFF666666) to "Orbi te está buscando..."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Text(status, color = dim, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Seguir con la mirada", color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "Usa la cámara frontal. Todo se procesa en tu celular: no se guardan ni se envían imágenes.",
                    color = dim, fontSize = 13.sp,
                )
            }
            Switch(checked = tracking, onCheckedChange = onToggleTracking)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sonidos", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Bips y chirridos cuando reacciona.", color = dim, fontSize = 13.sp)
            }
            Switch(checked = sound, onCheckedChange = { OrbiState.soundEnabled.value = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reaccionar a notificaciones", color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    if (notifications) "Activado. Orbi solo sabe que llegó algo; no lee el contenido."
                    else "Orbi se sorprende cuando te llega un mensaje. No lee el contenido.",
                    color = dim, fontSize = 13.sp,
                )
            }
            if (!notifications) {
                OutlinedButton(onClick = onNotificationAccess) { Text("Activar") }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onToggleBubble, modifier = Modifier.fillMaxWidth()) {
            Text(if (bubbleVisible) "Ocultar burbuja" else "Mostrar a Orbi en burbuja")
        }
        OutlinedButton(onClick = onNightMode, modifier = Modifier.fillMaxWidth()) {
            Text("Modo noche")
        }
        Text("Cosas que puedes hacerle", color = Color.White, fontWeight = FontWeight.Medium)
        Text(
            "• Sacudir el celular: se marea (si insistes, se enoja).\n" +
                "• Lanzar la burbuja con el dedo: vuela y rebota en los bordes.\n" +
                "• Mantener el dedo sobre él y frotar: lo acaricias.\n" +
                "• Guiñarle un ojo: te lo devuelve.\n" +
                "• Inclinar la cabeza: te imita.\n" +
                "• Tapar la cámara con la mano: se asusta y te busca.\n" +
                "• Inclinar el celular: sus ojos caen hacia el lado de abajo.\n" +
                "• Tocarlo muchas veces seguidas: se enoja.\n\n" +
                "En la burbuja: arrastra para mover, suelta sobre la X para cerrar y toca dos veces para volver aquí.",
            color = dim,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}
