package dev.watchmemory.recording.infrastructure.android

import android.app.ActivityManager
import android.content.Context

/** Historical entries are evidence, not automatically assigned as the cause of this run. */
class ProcessExitEvidence(private val context: Context) {
    fun snapshot(): Map<String, Any> = buildMap {
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
        put("exit_history_count", exits.size)
        put("exit_history_scope", "recent_package_exits_not_automatically_correlated")
        exits.forEachIndexed { index, exit ->
            val prefix = "exit_${index}_"
            put(prefix + "timestamp_ms", exit.timestamp)
            put(prefix + "reason", exit.reason)
            put(prefix + "status", exit.status)
            put(prefix + "importance", exit.importance)
            put(prefix + "pss_kb", exit.pss)
            put(prefix + "rss_kb", exit.rss)
            put(prefix + "process", exit.processName.orEmpty())
            put(prefix + "description", exit.description.orEmpty().take(1024))
        }
    }
}
