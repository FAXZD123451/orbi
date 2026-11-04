package com.orbi.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.orbi.app.brain.MoodEngine
import com.orbi.app.sense.MotionSense

/** Arranca el cerebro de Orbi en cuanto se abre la app y le cuenta cómo va la batería. */
class OrbiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MoodEngine.start()
        MotionSense.start(this)

        val battery = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> MoodEngine.onCharging()
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        OrbiState.batteryLow.value = level >= 0 && level * 100 / scale < 15 && !charging
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, battery, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
