package dev.koememo.telemetry

import android.content.Context
import android.util.Log

fun interface TelemetrySource {
    fun snapshot(): Map<String, Any>
}

/** Add device-specific sources with distinct field names. Preserve the first source on collisions. */
class DeviceTelemetry(private val sources: List<TelemetrySource>) : TelemetrySource {
    override fun snapshot(): Map<String, Any> = buildMap {
        for (source in sources) {
            try {
                source.snapshot().forEach { (key, value) -> putIfAbsent(key, value) }
            } catch (failure: Exception) {
                Log.w("KoeMemo", "Device telemetry unavailable", failure)
            }
        }
    }

    companion object {
        fun android(context: Context) = DeviceTelemetry(listOf(BatteryTelemetry(context)))
    }
}
