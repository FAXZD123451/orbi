package com.orbi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.orbi.app.assistant.Assistant
import com.orbi.app.assistant.LocalBrain
import com.orbi.app.brain.MoodEngine
import com.orbi.app.creator.CreatorActivity
import com.orbi.app.night.NightActivity
import com.orbi.app.onboarding.OnboardingScreen
import com.orbi.app.onboarding.OnboardingStep
import com.orbi.app.overlay.BubbleService
import com.orbi.app.sense.NotificationWatcher
import com.orbi.app.ui.HomeActions
import com.orbi.app.ui.HomeScreen
import com.orbi.app.ui.Palette
import com.orbi.app.ui.SettingsActions
import com.orbi.app.vision.FaceTracker
import kotlinx.coroutines.launch

/** Tema monocromo: blanco sobre negro, como la cara de Orbi. */
private val OrbiColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondaryContainer = Palette.Raised,
    onSecondaryContainer = Color.White,
    background = Palette.Background,
    surface = Palette.Surface,
    onSurface = Color.White,
)

class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Si acaba de dar la cámara o el micrófono, Orbi empieza a usarlos ya (aquí y en la burbuja).
            FaceTracker.rebind(this)
            BubbleService.refresh(this)
            Assistant.refresh()
        }

    /** Elegir el archivo del modelo de Gemma (por ejemplo, desde Descargas) para instalarlo. */
    private val pickBrain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = LocalBrain.installFrom(this@MainActivity, uri)
            Toast.makeText(
                this@MainActivity,
                if (ok) "¡Cerebro instalado!" else "No parece un modelo válido (debe ser el archivo .task de Gemma)",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun askMic(): Boolean {
        if (Assistant.hasMic(this)) return true
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        return false
    }

    /** Sube cada vez que la app vuelve al frente (para revisar permisos dados en Ajustes). */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val actions = HomeActions(
            onTalk = { if (askMic()) Assistant.talk(this) },
            onNightMode = { startActivity(Intent(this, NightActivity::class.java)) },
            onToggleBubble = ::toggleBubble,
            settings = SettingsActions(
                onToggleVoice = { on ->
                    OrbiState.voiceEnabled.value = on
                    if (on) askMic()
                    BubbleService.refresh(this)
                    Assistant.refresh()
                },
                onToggleTracking = ::setTracking,
                onNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onInstallBrain = { pickBrain.launch(arrayOf("*/*")) },
                onShowWelcome = { showWelcome() },
            ),
            onCreatorMode = {
                creatorToast?.cancel()
                startActivity(Intent(this, CreatorActivity::class.java))
            },
            onCreatorHint = { left ->
                creatorToast?.cancel()
                creatorToast = Toast.makeText(
                    this, if (left == 1) "Falta 1 toque para el modo creador" else "Faltan $left toques para el modo creador",
                    Toast.LENGTH_SHORT,
                ).also { it.show() }
            },
        )

        setContent {
            MaterialTheme(colorScheme = OrbiColors) {
                var onboarded by remember { mutableStateOf(Prefs.onboarded) }
                welcomeRequest = { onboarded = false }
                if (!onboarded) {
                    OnboardingScreen(onboardingSteps(), resumeTick) {
                        Prefs.onboarded = true
                        onboarded = true
                    }
                } else {
                    HomeScreen(actions)
                }
            }
        }
    }

    private var creatorToast: Toast? = null
    private var welcomeRequest: () -> Unit = {}
    private fun showWelcome() = welcomeRequest()

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Solo existe un Orbi: mientras la app está abierta, la isla o burbuja se esconde.
        OrbiState.appVisible.value = true
        MoodEngine.onAppOpened()
        FaceTracker.acquire(this, this)
        Assistant.attach(this)
        OrbiState.notificationsAllowed.value = NotificationWatcher.isAllowed(this)
        // Si la burbuja estaba abierta y Android la cerró, vuelve (escondida hasta que salgas de la app).
        if (Prefs.bubbleWanted && !OrbiState.bubbleVisible.value && Settings.canDrawOverlays(this)) {
            BubbleService.start(this)
        }
    }

    override fun onPause() {
        OrbiState.appVisible.value = false
        FaceTracker.release(this, this)
        Assistant.detach()
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
            title = "Para escucharte",
            body = "Cuando digas \"Oye Orbi\" te respondo. Escucho dentro de tu celular, sin internet: " +
                "no grabo ni envío nada, solo espero mi nombre.",
            actionLabel = "Permitir micrófono",
            isDone = { Assistant.hasMic(this) },
            action = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
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
            body = "Así puedo acompañarte mientras usas otras apps. En Ajustes, busca Orbi y " +
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
            body = "Di «Oye Orbi» y pregúntame lo que quieras. También puedes acariciarme, sacudirme " +
                "o taparme la cámara para jugar a las escondidas.",
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
        Toast.makeText(this, "Listo: Orbi aparecerá cuando salgas de la app", Toast.LENGTH_SHORT).show()
    }
}
