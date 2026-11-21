package dev.watchmemory.recording.presentation.settings

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import android.util.Log
import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.recording.application.WearStartCandidate
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Foreground comparison session. Only the standard sensor has documented on/off semantics. */
class WearSignalActivity : Activity(), SensorEventListener {
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val status by lazy { TextView(this).apply { gravity = Gravity.CENTER; setPadding(36, 20, 36, 20) } }
    private val latest = linkedMapOf<String, String>()
    private var candidate = WearStartCandidate()
    private var count = 0
    private var loggingFailed = false
    private var active = false

    override fun onResume() {
        super.onResume()
        val graph = AppGraph.get(this)
        if (!graph.settings.experimentsEnabled || graph.state.busy) { finish(); return }
        active = true
        candidate = WearStartCandidate()
        count = 0
        latest.clear()
        setContentView(status)
        val all = sensors.getSensorList(Sensor.TYPE_ALL)
        for ((type, name) in listOf(Sensor.STRING_TYPE_LOW_LATENCY_OFFBODY_DETECT to "标准",
                "com.samsung.sensor.low_power_offbody_detector" to "三星")) {
            val sensor = all.firstOrNull { it.stringType == type }
            val registered = sensor != null && try {
                sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: SecurityException) { false }
            latest[name] = when { sensor == null -> "无此传感器"; registered -> "等待事件"; else -> "注册被拒" }
        }
        record(JSONObject().put("event", "session_start").put("sources", JSONObject(latest as Map<*, *>)))
        render()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!active) return
        val value = e.values.firstOrNull() ?: return
        if (!value.isFinite()) return
        val standard = e.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT
        val triggered = standard && candidate.observe(value)
        if (triggered) count++
        latest[if (standard) "标准" else "三星"] = if (standard) when (value) {
            1f -> "已佩戴"; 0f -> "已摘下"; else -> "未知 ($value)"
        } else "原始值 $value"
        record(JSONObject().put("event", "wear_signal").put("type", e.sensor.stringType)
            .put("value", value.toDouble()).put("sensor_elapsed_ns", e.timestamp).put("start_candidate", triggered))
        render()
    }

    private fun render() {
        status.text = "佩戴触发对照\n\n" + latest.entries.joinToString("\n") { "${it.key}：${it.value}" } +
            "\n\n候选启动：${count} 次\n仅记录信号，离开页面结束" + if (loggingFailed) "\n日志写入失败" else ""
    }

    private fun record(row: JSONObject) {
        row.put("wall_ms", System.currentTimeMillis()).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
        try {
            val dir = File(filesDir, "diagnostics")
            dir.mkdirs()
            File(dir, "wear-signals.jsonl").appendText(row.toString() + "\n")
        } catch (e: IOException) {
            loggingFailed = true
            Log.w("KoeMemoWear", "Could not save signal", e)
        }
    }

    override fun onPause() {
        if (active) {
            active = false
            sensors.unregisterListener(this)
            record(JSONObject().put("event", "session_end"))
        }
        super.onPause()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
