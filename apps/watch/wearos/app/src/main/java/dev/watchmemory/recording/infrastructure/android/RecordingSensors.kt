package dev.watchmemory.recording.infrastructure.android

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray

/** Service-main-thread callbacks. Preserve sensor acquisition time even when delivery is batched. */
internal class RecordingSensors(context: Context, private val write: (String, Map<String, Any>) -> Unit) :
    SensorEventListener, AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val batch = ArrayList<JSONArray>()
    private var lastWrite = SystemClock.elapsedRealtime()
    private var closed = false
    private var lastMotionNs = Long.MIN_VALUE

    companion object {
        val heartPermission: String get() = if (android.os.Build.VERSION.SDK_INT >= 36)
            "android.permission.health.READ_HEART_RATE" else android.Manifest.permission.BODY_SENSORS
    }

    fun start(heartGranted: Boolean) {
        fun register(type: Int): String {
            return try {
                val sensor = manager.getDefaultSensor(type) ?: return "unavailable"
                if (manager.registerListener(this, sensor, 200000, 2000000)) "registered" else "rejected"
            } catch (e: RuntimeException) { e.javaClass.simpleName }
        }
        emit("sensor_status", mapOf("accelerometer" to register(Sensor.TYPE_ACCELEROMETER),
            "heart_rate" to if (heartGranted) register(Sensor.TYPE_HEART_RATE) else "permission_unavailable",
            "timebase" to "boottime_ns"))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (closed) return
        val type = event.sensor.type
        val n = if (type == Sensor.TYPE_ACCELEROMETER) 3 else 1
        if (event.values.size < n || (0 until n).any { !event.values[it].isFinite() }) return
        if (type == Sensor.TYPE_ACCELEROMETER) {
            if (lastMotionNs >= 0 && event.timestamp - lastMotionNs < 190000000L) return
            lastMotionNs = event.timestamp
        }
        val row = JSONArray().put(event.timestamp).put(type).put(event.accuracy)
        for (i in 0 until n) row.put(event.values[i].toDouble())
        batch.add(row)
        if (batch.size >= 64 || SystemClock.elapsedRealtime() - lastWrite >= 5000) flush()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun emit(event: String, fields: Map<String, Any>) {
        try { write(event, fields) } catch (e: Exception) { Log.w("KoeMemo", "Sensor log unavailable", e) }
    }
    private fun flush() {
        if (batch.isNotEmpty()) {
            emit("sensor_batch", mapOf("samples" to JSONArray(batch)))
            batch.clear()
        }
        lastWrite = SystemClock.elapsedRealtime()
    }
    override fun close() {
        closed = true
        manager.unregisterListener(this)
        flush()
    }
}
