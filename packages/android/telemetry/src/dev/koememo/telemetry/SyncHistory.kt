package dev.koememo.telemetry

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun showSyncHistory(activity: Activity) {
    val dialog = AlertDialog.Builder(activity).setTitle("最近同步统计")
        .setMessage("正在读取…").setPositiveButton("关闭", null).show()
    Thread {
        val message = runCatching { readHistory(File(activity.filesDir, "diagnostics")) }
            .getOrElse { "读取失败：${it.localizedMessage}" }
        activity.runOnUiThread { if (dialog.isShowing && !activity.isDestroyed) dialog.setMessage(message) }
    }.start()
}

private data class Attempt(val first: JSONObject, var last: JSONObject, var discharging: Boolean = true)

fun readHistory(directory: File): String {
    val attempts = linkedMapOf<String, Attempt>()
    for (name in listOf("sync.previous.jsonl", "sync.jsonl")) {
        val file = File(directory, name)
        if (!file.isFile) continue
        file.useLines { lines -> lines.forEach { line ->
            val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val key = row.optString("attempt")
            if (key.isEmpty()) return@forEach
            val entry = attempts.getOrPut(key) { Attempt(row, row) }
            entry.last = row
            entry.discharging = entry.discharging && row.optInt("battery_status", -1) == 3 &&
                row.optInt("battery_plugged", -1) == 0
        } }
    }
    if (attempts.isEmpty()) return "暂无记录。开启性能与耗电统计后，执行一次同步即可查看。"
    return attempts.values.sortedByDescending { it.last.optLong("timestamp_ms") }.take(5)
        .joinToString("\n\n") { describe(it) }
}

private fun describe(attempt: Attempt): String = buildString {
    val first = attempt.first
    val last = attempt.last
    val complete = first.optString("event") == "start" && last.optString("event") == "end"
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    appendLine(time.format(Date(last.optLong("timestamp_ms"))))
    val outcome = when (val reason = last.optString("outcome")) {
        "completed" -> "完成"
        "paused" -> "已暂停"
        "" -> "尚无结束记录"
        else -> reason
    }
    appendLine("$outcome · 确认 ${last.optInt("confirmed_segments")} 段")
    val elapsed = last.optLong("elapsed_ms")
    val kib = last.optLong("payload_bytes") / 1024.0
    appendLine(String.format(Locale.ROOT, "%.1f 秒 · %.1f KiB", elapsed / 1000.0, kib))
    if (complete && elapsed > 0) appendLine(String.format(Locale.ROOT, "平均 %.1f KiB/s", kib * 1000 / elapsed))
    appendLine("进程 CPU ${last.optLong("process_cpu_ms")} ms")
    fun delta(key: String): Long? {
        if (!complete || !first.has(key) || !last.has(key)) return null
        return last.getLong(key) - first.getLong(key)
    }
    val user = delta("uid_user_cpu_ms")
    val system = delta("uid_system_cpu_ms")
    if (user != null && system != null && user >= 0 && system >= 0)
        appendLine("UID CPU 用户态 $user ms · 内核态 $system ms")
    val wall = last.optJSONObject("phase_wall_ms")
    val thread = last.optJSONObject("phase_thread_cpu_ns")
    if (wall != null) for (key in wall.keys()) {
        val nanos = thread?.optLong(key, -1) ?: -1
        val cpu = if (nanos >= 0) " · CPU ${nanos / 1_000_000} ms" else ""
        appendLine("$key ${wall.optLong(key)} ms$cpu")
    }
    if (first.has("battery_percent") && last.has("battery_percent"))
        appendLine("整机电量 ${first.getInt("battery_percent")}% → ${last.getInt("battery_percent")}%")
    val charge = delta("charge_counter_uah_reported")
    append(when {
        !complete -> "样本不完整，无法估算放电量"
        !attempt.discharging -> "含供电或未知状态，未计算放电量"
        charge == null || charge > 0 || last.getLong("charge_counter_uah_reported") < 0 -> "放电计数不可用"
        else -> "整机电荷下降 ${-charge} µAh（设备报告）"
    })
}
