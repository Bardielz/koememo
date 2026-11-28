package dev.watchmemory.recording.infrastructure.android

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray

/** Service-main-thread callbacks. Preserve sensor acquisition time even when delivery is batched. */
internal class RecordingSensors(context: Context, private val write: (String, Map<String, Any>) -> Unit) :
    SensorEventListener2, AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val batch = ArrayList<JSONArray>()
    private var lastWrite = SystemClock.elapsedRealtime()
    private var closed = false
    private var lastMotionNs = Long.MIN_VALUE
    private val registered = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var finish: (() -> Unit)? = null
    private var cutoffNs = Long.MAX_VALUE
    private val flushTimeout = Runnable {
        emit("sensor_flush_timeout", mapOf("pending_types" to JSONArray(registered.toList())))
        finishDrain()
    }

    companion object {
        val heartPermission: String get() = if (android.os.Build.VERSION.SDK_INT >= 36)
            "android.permission.health.READ_HEART_RATE" else android.Manifest.permission.BODY_SENSORS
    }

    fun start(heartUnavailable: String?) {
        fun register(type: Int): String {
            return try {
                val sensor = manager.getDefaultSensor(type) ?: return "unavailable"
                if (!manager.registerListener(this, sensor, 200000, 2000000)) return "rejected"
                registered.add(type)
                "registered"
            } catch (e: RuntimeException) { e.javaClass.simpleName }
        }
        emit("sensor_status", mapOf("provider" to "android_sensor_manager",
            "accelerometer" to register(Sensor.TYPE_ACCELEROMETER),
            "heart_rate" to (heartUnavailable ?: register(Sensor.TYPE_HEART_RATE)),
            "timebase" to "boottime_ns"))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (closed || event.timestamp > cutoffNs) return
        val type = event.sensor.type
        val n = when (type) {
            Sensor.TYPE_ACCELEROMETER -> 3
            Sensor.TYPE_HEART_RATE -> 1
            else -> return
        }
        if (event.values.size < n) return
        if (type == Sensor.TYPE_ACCELEROMETER) {
            if ((0 until n).any { !event.values[it].isFinite() }) return
            if (lastMotionNs >= 0 && event.timestamp - lastMotionNs < 190000000L) return
            lastMotionNs = event.timestamp
        }
        val row = JSONArray().put(event.timestamp).put(type).put(event.accuracy)
        for (i in 0 until n) row.put(if (event.values[i].isFinite()) event.values[i].toDouble() else org.json.JSONObject.NULL)
        batch.add(row)
        if (batch.size >= 64 || SystemClock.elapsedRealtime() - lastWrite >= 5000) flush()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // Drain the hardware FIFO before unregistering. The audio worker has already finished.
    fun drain(onFinished: () -> Unit) {
        cutoffNs = SystemClock.elapsedRealtimeNanos()
        finish = onFinished
        if (registered.isNotEmpty() && try { manager.flush(this) } catch (_: RuntimeException) { false }) {
            handler.postDelayed(flushTimeout, 2500)
        } else finishDrain()
    }
    override fun onFlushCompleted(sensor: Sensor) {
        if (finish == null) return
        registered.remove(sensor.type)
        if (registered.isEmpty()) finishDrain()
    }
    private fun finishDrain() {
        val done = finish ?: return
        close()
        done()
    }
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
        if (closed) return
        closed = true
        finish = null
        handler.removeCallbacks(flushTimeout)
        try { manager.unregisterListener(this) }
        catch (e: RuntimeException) { Log.w("KoeMemo", "Could not unregister recording sensors", e) }
        finally { flush() }
    }
}
