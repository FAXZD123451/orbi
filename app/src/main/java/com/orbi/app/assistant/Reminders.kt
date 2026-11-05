package com.orbi.app.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.orbi.app.MainActivity
import com.orbi.app.R

/** Programa recordatorios con la alarma del sistema (funcionan aunque Orbi no esté abierto). */
object Reminders {
    const val EXTRA_TEXT = "text"
    private const val CHANNEL = "orbi_reminders"

    fun schedule(context: Context, text: String, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val id = (atMillis / 1000).toInt() xor text.hashCode()
        val intent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Hora exacta si Android lo permite; si no, aproximada (puede retrasarse unos minutos).
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
    }

    fun notify(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recordatorios de Orbi", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            text.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_orbi)
                .setContentTitle("Orbi te recuerda")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }
}

/** Llega la hora: notificación y, si Orbi está activo, te lo dice en voz alta. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(Reminders.EXTRA_TEXT) ?: return
        Reminders.notify(context, text)
        Assistant.announceReminder(context.applicationContext, text)
    }
}
