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
import android.content.res.Configuration
import androidx.core.animation.doOnEnd
import com.orbi.app.Prefs
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.animation.OvershootInterpolator
import com.orbi.app.BubbleMode
import kotlinx.coroutines.flow.drop
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
    private val bubbleSize get() = (OrbiState.floatSizeDp.value * density).toInt()
    private val barHeight get() = (OrbiState.barHeightDp.value * density).toInt()
    private val isTopBar get() = OrbiState.bubbleMode.value == BubbleMode.TOP_BAR
    private val closeSize get() = (CLOSE_DP * density).toInt()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        owner.start()
        val withCamera = goForeground()
        showBubble()
        OrbiState.bubbleVisible.value = true
        Prefs.bubbleWanted = true
        // Mientras la burbuja exista, Orbi te busca con la cámara aunque estés en otra app.
        // (Si Android revivió el servicio en segundo plano, no deja usar la cámara hasta abrir la app.)
        if (withCamera) FaceTracker.acquire(this, owner)

        // En modo noche la cara ocupa toda la pantalla: la burbuja se esconde.
        scope.launch {
            OrbiState.nightMode.collect { night ->
                bubble?.visibility = if (night) View.GONE else View.VISIBLE
            }
        }
        // Cambiaste de forma en la app (flotante ↔ barra superior): se vuelve a construir.
        scope.launch {
            OrbiState.bubbleMode.drop(1).collect {
                bubble?.let { wm.removeView(it) }
                bubble = null
                hideCloseTarget()
                showBubble()
            }
        }
        // Cambiaste el tamaño (con el control de la app): se ajusta al momento.
        scope.launch {
            OrbiState.floatSizeDp.drop(1).collect { if (!isTopBar) applyFloatingSize(bubbleSize) }
        }
        scope.launch {
            OrbiState.barHeightDp.drop(1).collect {
                val view = bubble ?: return@collect
                if (isTopBar && bubbleParams.height != barHeight) {
                    bubbleParams.height = barHeight
                    wm.updateViewLayout(view, bubbleParams)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> closeByUser()
            // Se dio el permiso de cámara o se cambió el seguimiento: actualiza el servicio.
            ACTION_REFRESH -> {
                if (goForeground()) FaceTracker.acquire(this, owner)
            }
        }
        // Si Android cierra el servicio por falta de memoria, lo vuelve a crear solo.
        return START_STICKY
    }

    /** El usuario quitó a Orbi a propósito: no hay que traerlo de vuelta. */
    private fun closeByUser() {
        Prefs.bubbleWanted = false
        stopSelf()
    }

    /** Al girar la pantalla, la burbuja se reacomoda para no quedar fuera de los bordes. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = bubble ?: return
        if (isTopBar) return // ocupa todo el ancho: se adapta sola
        bubbleParams.x = bubbleParams.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        bubbleParams.y = bubbleParams.y.coerceIn(0, (screenH - bubbleSize).coerceAtLeast(0))
        wm.updateViewLayout(view, bubbleParams)
        snapToEdge(view)
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

    /** Arranca el servicio en primer plano. Devuelve si quedó con acceso a la cámara. */
    private fun goForeground(): Boolean {
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification)
            return true
        }
        val wantsCamera = FaceTracker.hasPermission(this) && OrbiState.trackingEnabled.value
        if (wantsCamera) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
                return true
            } catch (e: SecurityException) {
                // Android no deja pedir la cámara si el servicio arrancó en segundo plano
                // (por ejemplo, al reiniciar el celular). La burbuja sigue, sin cámara.
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return false
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

    private fun showBubble() = if (isTopBar) showTopBar() else showFloating()

    /** Alto de la barra de estado de Android (la hora, la batería...). */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).toInt()
    }

    /**
     * Barra superior estilo "isla": todo el ancho, pegada arriba y con las esquinas de abajo
     * redondeadas. Se dibuja por debajo de la barra de estado de Android, así que la hora y los
     * íconos siguen viéndose encima y se pueden seguir bajando las notificaciones desde ahí.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showTopBar() {
        val statusBar = statusBarHeight()
        bubbleParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, barHeight, touchable = true).apply {
            x = 0
            y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            // Que también cubra la zona de la cámara frontal (el "notch" o agujero).
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val topInset = statusBar / density
        val view = ComposeView(this).apply {
            owner.attach(this)
            setContent {
                val shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black)
                ) {
                    // La cara va un poco por debajo de la barra de estado, con los ojos a los
                    // lados de la cámara frontal (como en el boceto).
                    LiveOrbiFace(
                        Modifier.fillMaxSize().padding(top = (topInset * 0.55f).dp),
                        zoom = 1.25f,
                    )
                }
            }
            setOnTouchListener(TopBarTouch())
            translationY = -barHeight.toFloat()
        }
        wm.addView(view, bubbleParams)
        bubble = view
        // Entra deslizándose desde arriba.
        view.animate().translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(0.8f)).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloating() {
        // Aparece donde la dejaste la última vez (o a la derecha, a un tercio de altura).
        val saved = Prefs.bubblePosition()
        bubbleParams = overlayParams(bubbleSize, bubbleSize, touchable = true).apply {
            x = (saved?.first ?: (screenW - bubbleSize - margin())).coerceIn(0, screenW - bubbleSize)
            y = (saved?.second ?: (screenH / 3)).coerceIn(0, screenH - bubbleSize)
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
            doOnEnd { Prefs.saveBubblePosition(bubbleParams.x, bubbleParams.y) }
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

    /** Cambia el tamaño de la burbuja flotante sin moverle el centro. */
    private fun applyFloatingSize(size: Int) {
        val view = bubble ?: return
        val cx = bubbleParams.x + bubbleParams.width / 2
        val cy = bubbleParams.y + bubbleParams.height / 2
        bubbleParams.width = size
        bubbleParams.height = size
        bubbleParams.x = (cx - size / 2).coerceIn(0, (screenW - size).coerceAtLeast(0))
        bubbleParams.y = (cy - size / 2).coerceIn(0, (screenH - size).coerceAtLeast(0))
        wm.updateViewLayout(view, bubbleParams)
    }

    /**
     * Gestos de la barra superior:
     *  - Tocar: reacciona. Doble toque: abre la app.
     *  - Arrastrar hacia arriba o abajo: cambia el alto de la barra.
     *  - Deslizar rápido hacia arriba: la oculta.
     *  - Mantener quieto medio segundo y frotar: acariciar.
     */
    private inner class TopBarTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val main = Handler(Looper.getMainLooper())
        private var downY = 0f
        private var startHeight = 0
        private var resizing = false
        private var petting = false
        private var velocity: VelocityTracker? = null
        private val minH get() = (Prefs.BAR_HEIGHT_MIN * density).toInt()
        private val maxH get() = (Prefs.BAR_HEIGHT_MAX * density).toInt()

        private val startPetting = Runnable {
            if (!resizing) { petting = true; MoodEngine.onPetStart() }
        }

        private val gestures = GestureDetector(this@BubbleService, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { MoodEngine.onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { openApp(); return true }
        }).apply { setIsLongpressEnabled(false) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (!petting) gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startHeight = bubbleParams.height
                    resizing = false
                    petting = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    main.postDelayed(startPetting, PET_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dy = e.rawY - downY
                    if (petting) {
                        MoodEngine.onPetting()
                    } else {
                        if (!resizing && kotlin.math.abs(dy) > slop) {
                            resizing = true
                            main.removeCallbacks(startPetting)
                        }
                        if (resizing) {
                            bubbleParams.height = (startHeight + dy.toInt()).coerceIn(minH, maxH)
                            wm.updateViewLayout(v, bubbleParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(startPetting)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    when {
                        petting -> MoodEngine.onPetEnd()
                        resizing && vy < -HIDE_SPEED * density -> {
                            // Deslizar rápido hacia arriba: se esconde subiendo.
                            v.animate().translationY(-v.height.toFloat()).setDuration(200)
                                .withEndAction { closeByUser() }.start()
                        }
                        resizing -> OrbiState.barHeightDp.value = bubbleParams.height / density
                    }
                    resizing = false
                    petting = false
                    velocity?.recycle()
                    velocity = null
                }
            }
            return true
        }
    }

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
        private var scaling = false
        private var velocity: VelocityTracker? = null

        private val startPetting = Runnable {
            if (!dragging && !scaling) {
                petting = true
                MoodEngine.onPetStart()
            }
        }

        // Pellizco con dos dedos: cambia el tamaño de la burbuja.
        private val pinch = ScaleGestureDetector(this@BubbleService,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val minPx = Prefs.FLOAT_SIZE_MIN * density
                    val maxPx = Prefs.FLOAT_SIZE_MAX * density
                    val size = (bubbleParams.width * d.scaleFactor).coerceIn(minPx, maxPx).toInt()
                    applyFloatingSize(size)
                    return true
                }
            })

        private val gestures = GestureDetector(this@BubbleService, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { MoodEngine.onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { openApp(); return true }
        }).apply { setIsLongpressEnabled(false) }

        // Se usan coordenadas absolutas (rawX/rawY) porque la ventana se mueve bajo el dedo.
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            pinch.onTouchEvent(e)
            if (e.pointerCount > 1 || scaling) {
                // Con dos dedos solo se cambia el tamaño: nada de arrastrar ni acariciar.
                if (!scaling) {
                    scaling = true
                    main.removeCallbacks(startPetting)
                    if (dragging) { hideCloseTarget(); dragging = false }
                }
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    scaling = false
                    OrbiState.floatSizeDp.value = bubbleParams.width / density
                    snapToEdge(v)
                }
                return true
            }
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
                            close -> closeByUser()
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
        private const val HIDE_SPEED = 1800f   // dp/s hacia arriba para ocultar la barra
        private const val FLING_SPEED = 900f   // dp/s para que soltar cuente como lanzamiento
        private const val STOP_SPEED = 220f    // px/s: por debajo, deja de volar
        private const val BOUNCE = 0.72f       // energía que conserva al rebotar
        private const val FRICTION = 0.975f    // frenado por cuadro (a 60 fps)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        /** El usuario la ocultó desde la app. */
        fun stop(context: Context) {
            Prefs.bubbleWanted = false
            context.stopService(Intent(context, BubbleService::class.java))
        }

        fun refresh(context: Context) {
            if (OrbiState.bubbleVisible.value) {
                context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
