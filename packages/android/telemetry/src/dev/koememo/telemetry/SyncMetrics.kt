package dev.koememo.telemetry

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One transfer attempt. Phase transitions and finish run on the transfer worker thread. */
class SyncMetrics(context: Context, private val side: String, private val session: String) {
    private val health = context.getSystemService(android.os.health.SystemHealthManager::class.java)
    private val battery = BatteryTelemetry(context)
    private val directory = File(context.filesDir, "diagnostics")
    private val attempt = UUID.randomUUID().toString()
    private val started = SystemClock.elapsedRealtime()
    private val processStart = Process.getElapsedCpuTime()
    private val wallTimes = mutableMapOf<String, Long>()
    private val cpuTimes = mutableMapOf<String, Long>()
    private val payload = AtomicLong()
    private var phase = "setup"
    private var phaseStart = started
    private var cpuStart = Debug.threadCpuTimeNanos()
    private var acknowledged = 0
    private var resumed = 0
    private var alreadyPresent = 0
    private var finished = false
    private val sampler = java.util.Timer("sync-metrics", true)

    init {
        write("start")
        sampler.schedule(object : java.util.TimerTask() {
            override fun run() = sample()
        }, 60_000L, 60_000L)
    }

    @Synchronized private fun sample() {
        if (!finished) write("sample")
    }

    @Synchronized fun enter(next: String) {
        val now = SystemClock.elapsedRealtime()
        val cpu = Debug.threadCpuTimeNanos()
        wallTimes[phase] = (wallTimes[phase] ?: 0) + now - phaseStart
        cpuTimes[phase] = (cpuTimes[phase] ?: 0) + cpu - cpuStart
        phaseStart = now
        cpuStart = cpu
        phase = next
    }

    fun copied(count: Int) { payload.addAndGet(count.toLong()) }

    @Synchronized fun confirmed(offset: Long, size: Long) {
        acknowledged++
        if (offset == size) alreadyPresent++ else if (offset > 0) resumed++
    }

    @Synchronized fun finish(outcome: String) {
        if (finished) return
        finished = true
        sampler.cancel()
        enter("finished")
        write("end", outcome)
    }

    private fun write(event: String, outcome: String? = null) {
        runCatching {
            val row = JSONObject(battery.snapshot()).apply {
                if (event != "sample") runCatching {
                    val uid = health.takeMyUidSnapshot()
                    fun cpu(key: Int, name: String) {
                        if (uid.hasMeasurement(key)) put(name, uid.getMeasurement(key))
                    }
                    cpu(android.os.health.UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS, "uid_user_cpu_ms")
                    cpu(android.os.health.UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS, "uid_system_cpu_ms")
                }
                put("v", 1)
                put("side", side)
                put("session", session)
                put("attempt", attempt)
                put("event", event)
                put("timestamp_ms", System.currentTimeMillis())
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("process_cpu_ms", Process.getElapsedCpuTime() - processStart)
                put("phase", phase)
                put("payload_bytes", payload.get())
                put("confirmed_segments", acknowledged)
                put("resumed_segments", resumed)
                put("full_offset_segments", alreadyPresent)
                if (outcome != null) {
                    put("outcome", outcome)
                    put("phase_wall_ms", JSONObject(wallTimes.toMap()))
                    put("phase_thread_cpu_ns", JSONObject(cpuTimes.toMap()))
                }
            }
            synchronized(fileLock) {
                check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, "sync.jsonl")
                if (file.length() >= 1024 * 1024) {
                    android.system.Os.rename(file.path, File(directory, "sync.previous.jsonl").path)
                }
                file.appendText(row.toString() + "\n")
            }
        }.onFailure { Log.w("KoeMemoMetrics", "Failed to save $event sample", it) }
    }

    private companion object { val fileLock = Any() }
}
