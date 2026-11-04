package com.orbi.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.orbi.app.MainActivity
import com.orbi.app.OrbiState
import com.orbi.app.R
import com.orbi.app.brain.MoodEngine
import com.orbi.app.face.LiveOrbiFace
import com.orbi.app.vision.FaceTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Mantiene a Orbi flotando sobre las demás apps.
 * - Arrastrar: lo mueve; al soltarlo se pega al borde más cercano.
 * - Soltarlo sobre la X de abajo: lo cierra.
 * - Un toque: Orbi reacciona. Doble toque: abre la app.
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private val owner = OverlayOwner()
    private val scope = MainScope()

    private var bubble: ComposeView? = null
    private var closeTarget: ComposeView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var closeParams: WindowManager.LayoutParams
    private val nearClose = mutableStateOf(false)
    private var flingJob: Job? = null

    private val density get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels
    private val bubbleSize get() = (BUBBLE_DP * density).toInt()
    private val closeSize get() = (CLOSE_DP * density).toInt()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        owner.start()
        goForeground()
        showBubble()
        OrbiState.bubbleVisible.value = true
        // Mientras la burbuja exista, Orbi te busca con la cámara aunque estés en otra app.
        FaceTracker.acquire(this, owner)

        // En modo noche la cara ocupa toda la pantalla: la burbuja se esconde.
        scope.launch {
            OrbiState.nightMode.collect { night ->
                bubble?.visibility = if (night) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            // Se dio el permiso de cámara o se cambió el seguimiento: actualiza el servicio.
            ACTION_REFRESH -> {
                goForeground()
                FaceTracker.acquire(this, owner)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        FaceTracker.release(this, owner)
        scope.cancel()
        bubble?.let { wm.removeView(it) }
        closeTarget?.let { wm.removeView(it) }
        bubble = null
        closeTarget = null
        owner.stop()
        OrbiState.bubbleVisible.value = false
        super.onDestroy()
    }

    // ---------- Notificación obligatoria del servicio en primer plano ----------

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Orbi en pantalla", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_orbi)
            .setContentTitle("Orbi está contigo")
            .setContentText("Arrástralo a la X para ocultarlo")
            .setContentIntent(open)
            .addAction(0, "Ocultar", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // El tipo "camera" solo se declara si hay permiso; si no, Android cerraría el servicio.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (FaceTracker.hasPermission(this) && OrbiState.trackingEnabled.value) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------- Burbuja ----------

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        bubbleParams = overlayParams(bubbleSize, bubbleSize, touchable = true).apply {
            x = screenW - bubbleSize - margin()
            y = screenH / 3
        }
        val view = ComposeView(this).apply {
            owner.attach(this)
            setContent {
                val shape = RoundedCornerShape(30.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black)
                        .border(1.5.dp, Color.White.copy(alpha = 0.18f), shape)
                ) {
                    LiveOrbiFace(Modifier.fillMaxSize().padding(4.dp), zoom = 1.55f)
                }
            }
            setOnTouchListener(BubbleTouch())
        }
        wm.addView(view, bubbleParams)
        bubble = view
    }

    private fun margin() = (8 * density).toInt()

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    /**
     * Lanzamiento con física simple: la burbuja sigue volando con la velocidad del dedo,
     * frena poco a poco y rebota en los bordes de la pantalla.
     */
    private fun fling(view: View, startVx: Float, startVy: Float) {
        flingJob?.cancel()
        MoodEngine.onFlingStart()
        flingJob = scope.launch {
            var vx = startVx
            var vy = startVy
            var x = bubbleParams.x.toFloat()
            var y = bubbleParams.y.toFloat()
            val maxX = (screenW - bubbleSize).toFloat()
            val maxY = (screenH - bubbleSize).toFloat()
            var bounces = 0
            var last = SystemClock.uptimeMillis()
            while (hypot(vx, vy) > STOP_SPEED && bubble != null) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                val dt = (now - last).coerceAtMost(40) / 1000f
                last = now
                x += vx * dt
                y += vy * dt
                if (x < 0f) { x = 0f; vx = -vx * BOUNCE; bounces++ }
                if (x > maxX) { x = maxX; vx = -vx * BOUNCE; bounces++ }
                if (y < 0f) { y = 0f; vy = -vy * BOUNCE; bounces++ }
                if (y > maxY) { y = maxY; vy = -vy * BOUNCE; bounces++ }
                val friction = FRICTION.pow(dt * 60f)
                vx *= friction
                vy *= friction
                bubbleParams.x = x.toInt()
                bubbleParams.y = y.toInt()
                wm.updateViewLayout(view, bubbleParams)
            }
            MoodEngine.onFlingEnd(bounces)
            snapToEdge(view)
        }
    }

    /** Pega la burbuja al borde izquierdo o derecho con una animación corta. */
    private fun snapToEdge(view: View) {
        val targetX = if (bubbleParams.x + bubbleSize / 2 < screenW / 2) margin()
        else screenW - bubbleSize - margin()
        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 220
            addUpdateListener {
                if (bubble == null) return@addUpdateListener
                bubbleParams.x = it.animatedValue as Int
                wm.updateViewLayout(view, bubbleParams)
            }
            start()
        }
    }

    // ---------- Zona para cerrar (la X de abajo) ----------

    private fun closeCenter() = Offset(screenW / 2f, screenH - closeSize / 2f - 110 * density)

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val c = closeCenter()
        closeParams = overlayParams(closeSize, closeSize, touchable = false).apply {
            x = (c.x - closeSize / 2).toInt()
            y = (c.y - closeSize / 2).toInt()
        }
        val view = ComposeView(this).apply {
            owner.attach(this)
            setContent {
                val near by nearClose
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxSize(if (near) 1f else 0.8f)
                            .clip(CircleShape)
                            .background(if (near) Color(0xFFE5484D) else Color(0xCC222222))
                    ) {
                        Canvas(Modifier.fillMaxSize().scale(0.35f)) {
                            val s = 4.dp.toPx()
                            drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), s, StrokeCap.Round)
                            drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        wm.addView(view, closeParams)
        closeTarget = view
    }

    private fun hideCloseTarget() {
        closeTarget?.let { wm.removeView(it) }
        closeTarget = null
        nearClose.value = false
    }

    private fun bubbleIsOverClose(): Boolean {
        val c = closeCenter()
        val bx = bubbleParams.x + bubbleSize / 2f
        val by = bubbleParams.y + bubbleSize / 2f
        return hypot(bx - c.x, by - c.y) < CLOSE_RADIUS_DP * density
    }

    // ---------- Gestos ----------

    /**
     * Gestos de la burbuja:
     *  - Tocar: reacciona. Doble toque: abre la app.
     *  - Arrastrar: la mueve (y aparece la X para cerrar). Soltar rápido: la lanza.
     *  - Mantener quieto medio segundo y luego frotar: acariciar (no la mueve).
     */
    private inner class BubbleTouch : View.OnTouchListener {
        private val config = ViewConfiguration.get(this@BubbleService)
        private val slop = config.scaledTouchSlop
        private val main = Handler(Looper.getMainLooper())
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var petting = false
        private var velocity: VelocityTracker? = null

        private val startPetting = Runnable {
            if (!dragging) {
                petting = true
                MoodEngine.onPetStart()
            }
        }

        private val gestures = GestureDetector(this@BubbleService, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { MoodEngine.onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { openApp(); return true }
        }).apply { setIsLongpressEnabled(false) }

        // Se usan coordenadas absolutas (rawX/rawY) porque la ventana se mueve bajo el dedo.
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (!petting) gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    flingJob?.cancel()
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    petting = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain()
                    track(e)
                    main.postDelayed(startPetting, PET_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (petting) {
                        MoodEngine.onPetting()
                    } else {
                        if (!dragging && hypot(dx, dy) > slop) {
                            dragging = true
                            main.removeCallbacks(startPetting)
                            showCloseTarget()
                        }
                        if (dragging) {
                            bubbleParams.x = startX + dx.toInt()
                            bubbleParams.y = startY + dy.toInt()
                            wm.updateViewLayout(v, bubbleParams)
                            nearClose.value = bubbleIsOverClose()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(startPetting)
                    if (petting) {
                        MoodEngine.onPetEnd()
                    } else if (dragging) {
                        val close = bubbleIsOverClose()
                        hideCloseTarget()
                        val tracker = velocity
                        tracker?.computeCurrentVelocity(1000)
                        val vx = tracker?.xVelocity ?: 0f
                        val vy = tracker?.yVelocity ?: 0f
                        when {
                            close -> stopSelf()
                            hypot(vx, vy) > FLING_SPEED * density -> fling(v, vx, vy)
                            else -> snapToEdge(v)
                        }
                    }
                    dragging = false
                    petting = false
                    velocity?.recycle()
                    velocity = null
                }
            }
            return true
        }

        /** El VelocityTracker necesita coordenadas de pantalla, no de la ventana que se mueve. */
        private fun track(e: MotionEvent) {
            val copy = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
            velocity?.addMovement(copy)
            copy.recycle()
        }
    }

    companion object {
        private const val CHANNEL_ID = "orbi_bubble"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.orbi.app.STOP_BUBBLE"
        private const val ACTION_REFRESH = "com.orbi.app.REFRESH_BUBBLE"
        private const val BUBBLE_DP = 96
        private const val CLOSE_DP = 72
        private const val CLOSE_RADIUS_DP = 80
        private const val PET_DELAY_MS = 450L
        private const val FLING_SPEED = 900f   // dp/s para que soltar cuente como lanzamiento
        private const val STOP_SPEED = 220f    // px/s: por debajo, deja de volar
        private const val BOUNCE = 0.72f       // energía que conserva al rebotar
        private const val FRICTION = 0.975f    // frenado por cuadro (a 60 fps)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }

        fun refresh(context: Context) {
            if (OrbiState.bubbleVisible.value) {
                context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
