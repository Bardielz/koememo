package dev.watchmemory.recording.infrastructure.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Low-frequency snapshot; missing values are omitted, never represented as zero. */
class BatteryTelemetry(private val context: Context) {
    private val manager = context.getSystemService(BatteryManager::class.java)
    fun snapshot(): Map<String, Any> = buildMap {
        fun property(key: String, id: Int) {
            val value = manager.getIntProperty(id)
            if (value != Int.MIN_VALUE) put(key, value)
        }
        property("charge_counter_uah_reported", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // OEM units/accuracy require validation before converting to power.
        property("current_now_raw", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        property("current_average_raw", BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
            fun extra(key: String, name: String) {
                if (intent.hasExtra(name)) put(key, intent.getIntExtra(name, -1))
            }
            extra("battery_plugged", BatteryManager.EXTRA_PLUGGED)
            extra("battery_status", BatteryManager.EXTRA_STATUS)
            extra("battery_voltage_mv", BatteryManager.EXTRA_VOLTAGE)
            extra("battery_temperature_tenths_c", BatteryManager.EXTRA_TEMPERATURE)
        }
    }
}
