package dev.watchmemory.recording.presentation.settings

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.koememo.samsung.SamsungPerformance
import java.io.File
import org.json.JSONObject

internal object PerformanceProbe {
    @Volatile var result: String? = null
        private set

    /** Called on a worker. Concurrent settings instances share one probe per process. */
    @Synchronized fun run(context: Context): String {
        result?.let { return it }
        val start = SystemClock.elapsedRealtime()
        val event = JSONObject().put("event", "samsung_performance_probe")
            .put("model", Build.MODEL).put("build", Build.DISPLAY).put("time_ms", System.currentTimeMillis())
        fun failed(error: Throwable): String {
            event.put("status", "error").put("exception", error.javaClass.name)
            return "初始化异常：${error.javaClass.simpleName}"
        }
        val message = try {
            val (status, version) = SamsungPerformance.probe(context)
            event.put("status", status).put("sdk_version", version)
            when (status) {
                "initialized" -> "初始化成功 · SDK $version"
                "returned_false" -> "初始化返回 false"
                else -> "当前构建未包含三星 SDK"
            }
        } catch (error: Exception) {
            failed(error)
        } catch (error: LinkageError) {
            failed(error)
        }
        event.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
        Log.i("KoeMemoSPerf", event.toString())
        try {
            val directory = File(context.filesDir, "diagnostics")
            directory.mkdirs()
            File(directory, "samsung-performance.jsonl").appendText(event.toString() + "\n")
        } catch (error: Exception) {
            Log.w("KoeMemoSPerf", "Could not persist probe result", error)
        }
        result = message
        return message
    }
}
