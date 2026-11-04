package com.orbi.app.sense

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.orbi.app.OrbiState
import com.orbi.app.brain.MoodEngine

/**
 * Avisa a Orbi cuando llega una notificación nueva de otra app, para que reaccione.
 *
 * Privacidad: no lee el contenido (ni título ni texto). Solo sabe que "llegó algo".
 * Más adelante, cuando Orbi pueda leerte mensajes, será con tu permiso explícito.
 */
class NotificationWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        OrbiState.notificationsAllowed.value = true
    }

    override fun onListenerDisconnected() {
        OrbiState.notificationsAllowed.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return           // las de Orbi no cuentan
        if (sbn.isOngoing) return                             // música, descargas, llamadas en curso...
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // resumen de grupo (evita doble aviso)
        MoodEngine.onNotification()
    }

    companion object {
        fun isAllowed(context: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
