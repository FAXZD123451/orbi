package com.orbi.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.orbi.app.Prefs

/**
 * Al encender el celular (o al actualizar Orbi), trae la burbuja de vuelta
 * si la dejaste abierta. Si la habías cerrado tú, no hace nada.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Prefs.init(context.applicationContext) // ya lo hizo OrbiApp; no pasa nada si se repite
        if (!Prefs.bubbleWanted || !Settings.canDrawOverlays(context)) return
        try {
            BubbleService.start(context)
        } catch (e: Exception) {
            // Algunas marcas bloquean arrancar servicios al encender. Volverá al abrir la app.
            Log.w("BootReceiver", "No se pudo traer la burbuja", e)
        }
    }
}
