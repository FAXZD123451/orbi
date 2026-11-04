package com.orbi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.orbi.app.onboarding.OnboardingScreen
import com.orbi.app.onboarding.OnboardingStep
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

    /** Sube cada vez que la app vuelve al frente (para revisar permisos dados en Ajustes). */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var onboarded by remember { mutableStateOf(Prefs.onboarded) }
                if (!onboarded) {
                    OnboardingScreen(onboardingSteps(), resumeTick) {
                        Prefs.onboarded = true
                        onboarded = true
                    }
                } else {
                    HomeScreen(
                        onToggleBubble = ::toggleBubble,
                        onNightMode = { startActivity(Intent(this, NightActivity::class.java)) },
                        onToggleTracking = ::setTracking,
                        onNotificationAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onShowWelcome = { onboarded = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        MoodEngine.onAppOpened()
        FaceTracker.acquire(this, this)
        OrbiState.notificationsAllowed.value = NotificationWatcher.isAllowed(this)
        // Si la burbuja estaba abierta y Android la cerró, vuelve al abrir la app (ya con cámara).
        if (Prefs.bubbleWanted && !OrbiState.bubbleVisible.value && Settings.canDrawOverlays(this)) {
            BubbleService.start(this)
        }
    }

    override fun onPause() {
        FaceTracker.release(this, this)
        super.onPause()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Los pasos de la bienvenida, cada uno con su permiso y la explicación de para qué sirve. */
    @SuppressLint("BatteryLife")
    private fun onboardingSteps() = listOf(
        OnboardingStep(
            title = "¡Hola! Soy Orbi",
            body = "Voy a vivir en tu pantalla. Puedo seguirte con la mirada, reaccionar cuando me tocas " +
                "o me sacudes, y acompañarte de noche. Para eso necesito unos permisos; te explico cada uno.",
        ),
        OnboardingStep(
            title = "Para mirarte",
            body = "Uso la cámara frontal para saber dónde estás, si sonríes o si me guiñas un ojo. " +
                "Todo se procesa en tu celular: no guardo ni envío imágenes.",
            actionLabel = "Permitir cámara",
            isDone = { granted(Manifest.permission.CAMERA) },
            action = { permissions.launch(arrayOf(Manifest.permission.CAMERA)) },
        ),
        OnboardingStep(
            title = "Para quedarme contigo",
            body = "Android exige una notificación fija cuando una app sigue activa en segundo plano. " +
                "Desde ahí también puedes ocultarme.",
            actionLabel = "Permitir notificaciones",
            isDone = {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
            },
            action = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
        ),
        OnboardingStep(
            title = "Para flotar sobre tus apps",
            body = "Así puedo estar en una burbuja mientras usas otras apps. En Ajustes, busca Orbi y " +
                "activa \"Mostrar sobre otras apps\". Luego vuelve aquí.",
            actionLabel = "Abrir Ajustes",
            isDone = { Settings.canDrawOverlays(this) },
            action = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            },
        ),
        OnboardingStep(
            title = "Para que no me duerman",
            body = "Algunos celulares cierran las apps en segundo plano para ahorrar batería. " +
                "Si me dejas fuera de ese ahorro, no desapareceré de tu pantalla.",
            actionLabel = "No optimizar a Orbi",
            isDone = { ignoresBatteryOptimizations() },
            action = {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
                )
            },
        ),
        OnboardingStep(
            title = "Para enterarme de tus mensajes",
            body = "Si quieres, me sorprendo cuando te llega una notificación. Solo sé que llegó algo: " +
                "no leo de quién es ni qué dice. Es opcional.",
            actionLabel = "Activar",
            optional = true,
            isDone = { NotificationWatcher.isAllowed(this) },
            action = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        ),
        OnboardingStep(
            title = "¡Todo listo!",
            body = "Acaríciame, sacúdeme, lánzame o tápame la cámara para jugar a las escondidas. " +
                "Y si pones el celular boca abajo, me echo una siesta.",
        ),
    )

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
    onShowWelcome: () -> Unit,
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
        TextButton(onClick = onShowWelcome) { Text("Ver la bienvenida y los permisos otra vez") }
    }
}
