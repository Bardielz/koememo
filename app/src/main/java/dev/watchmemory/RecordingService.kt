package dev.watchmemory

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service(), CaptureEngine.Observer {
    companion object {
        const val START = "start"
        const val PAUSE = "pause"
        const val FINISH = "finish"
        const val MARK = "mark"
        const val PREFS = "recording"
        @Volatile var busy = false
        @Volatile var status = "准备记录"
        @Volatile var db = -90.0
        @Volatile var muted = false
        @Volatile var startedAt = 0L
        @Volatile var frames = 0L
        @Volatile var lastFrameAt = 0L
        fun prefs(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        fun root(context: Context) = File(context.filesDir, "recordings")
        @Synchronized fun event(directory: File, type: String, fields: Map<String, Any> = emptyMap()) {
            val data = JSONObject().put("event", type).put("wall_ms", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime())
            fields.forEach { (key, value) -> data.put(key, value) }
            FileOutputStream(File(directory, "events.jsonl"), true).use {
                it.write((data.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var engine: CaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: File? = null
    private var ending = false
    private var stopReason: String? = null
    @Volatile private var lastLoudAt = 0L
    private var lastTelemetry = 0L
    private var lastResources = 0L
    private var cachedBattery = -1
    private var cachedFree = 0L
    private var lastNotice = ""
    private val monitor = object : Runnable {
        override fun run() {
            if (!busy) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastResources >= 30000) {
                cachedBattery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                cachedFree = root(this@RecordingService).usableSpace
                lastResources = now
            }
            val battery = cachedBattery
            if (stopReason == null) {
                status = when {
                    muted -> "麦克风被系统静音"
                    now - lastFrameAt > 5000 -> "等待音频数据"
                    now - lastLoudAt > 30000 -> "声音很轻，请检查收音"
                    else -> "正在录音"
                }
                when {
                    cachedFree < 256L * 1024 * 1024 -> requestStop("剩余空间不足 256 MB，已停止")
                    battery in 0..8 -> requestStop("电量低于 9%，已停止")
                    now - lastFrameAt > 30000 -> requestStop("超过 30 秒无编码数据，已停止")
                }
            }
            if (now - lastTelemetry >= 60000) {
                if (stopReason == null) wakeLock?.acquire(180_000L)
                log("telemetry", mapOf("battery_percent" to battery, "dbfs" to db,
                    "frames" to frames, "system_silenced" to muted,
                    "free_bytes" to cachedFree,
                    "thermal_status" to getSystemService(PowerManager::class.java).currentThermalStatus))
                lastTelemetry = now
            }
            if (status != lastNotice) {
                getSystemService(NotificationManager::class.java).notify(1, notification(status))
                lastNotice = status
            }
            handler.postDelayed(this, 5000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("recording", "录音状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!busy) begin()
            MARK -> if (busy && stopReason == null) {
                log("marker", mapOf("run_ms" to (SystemClock.elapsedRealtime() - startedAt)))
            }
            PAUSE -> if (busy) requestStop("已暂停") else stopSelf()
            FINISH -> {
                ending = true
                if (busy) requestStop("本次记录已结束") else {
                    prefs(this).getString("session", null)?.let { event(File(root(this), it), "session_finished") }
                    prefs(this).edit().remove("session").putString("status", "本次记录已结束").apply()
                    status = "本次记录已结束"
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY // Do not silently restart microphone from background.
    }
    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "需要麦克风权限"
            prefs(this).edit().putString("status", status).apply()
            stopSelf()
            return
        }
        try {
            startForeground(1, notification("正在启动录音"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val appSettings = AppSettings(this)
            val audioSource = appSettings.effectiveSource()
            val sampleRate = appSettings.effectiveSampleRate()
            val encoding = appSettings.effectiveEncoding()
            val bitrate = appSettings.effectiveBitrate()
            val settings = prefs(this)
            val sessionName = settings.getString("session", null) ?: (
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8))
            val directory = File(root(this), sessionName)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
            check(directory.usableSpace >= 256L * 1024 * 1024) { "Storage below 256 MB" }
            session = directory
            val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            check(battery !in 0..8) { "Battery below 9%" }
            lastResources = 0L
            lastTelemetry = 0L
            cachedBattery = battery
            cachedFree = directory.usableSpace
            startedAt = SystemClock.elapsedRealtime()
            lastFrameAt = startedAt
            lastLoudAt = startedAt
            frames = 0
            muted = false
            ending = false
            stopReason = null
            status = "正在启动录音"
            busy = true
            settings.edit().putString("session", sessionName).putBoolean("recording", true)
                .putString("status", status).commit()
            val prefix = "run-" + UUID.randomUUID().toString().take(8)
            event(directory, "run_started", mapOf("run" to prefix, "battery_percent" to battery,
                "experiments_enabled" to appSettings.experimentsEnabled, "audio_source" to audioSource.name,
                "unprocessed_supported" to appSettings.rawSupported, "sample_rate" to sampleRate, "encoding" to encoding.name, "bitrate" to bitrate, "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT, "abis" to Build.SUPPORTED_ABIS.joinToString()))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "watchmemory:recording").apply { setReferenceCounted(false); acquire(180_000L) }
            val capture = CaptureEngine(this, audioSource, sampleRate, encoding, bitrate)
            engine = capture
            handler.post(monitor)
            Thread({
                var failure: String? = null
                try {
                    val onClosed: (String, Long, Long, Long, String) -> Unit = { name, first, last, bytes, hash ->
                        event(directory, "segment_closed", mapOf("file" to name, "first_pts_us" to first,
                            "last_pts_us" to last, "bytes" to bytes, "sha256" to hash, "run" to prefix, "sample_rate" to sampleRate, "encoding" to encoding.name, "bitrate" to bitrate))
                    }
                    val writer: RecordingWriter = if (encoding == RecordingEncoding.AAC)
                        SegmentWriter(directory, prefix, sampleRate = sampleRate, onClosed = onClosed)
                    else OpusSegmentWriter(directory, prefix, onClosed = onClosed)
                    writer.use { capture.record(it) }
                } catch (e: Exception) { failure = "录音异常：${e.message ?: e.javaClass.simpleName}" }
                val result = failure
                handler.post { complete(result) }
            }, "audio-capture").start()
        } catch (e: Exception) { complete("启动失败：${e.message ?: e.javaClass.simpleName}") }
    }
    private fun requestStop(reason: String) {
        if (stopReason != null) return
        stopReason = reason
        status = "正在保存…"
        engine?.requestStop()
    }
    private fun complete(error: String?) {
        handler.removeCallbacks(monitor)
        val finalStatus = error ?: stopReason ?: "录音已停止"
        val duration = if (busy) SystemClock.elapsedRealtime() - startedAt else 0
        runCatching { session?.let { event(it, if (error != null) "error" else "run_stopped",
            mapOf("reason" to finalStatus, "duration_ms" to duration, "frames" to frames,
                "battery_percent" to getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))) } }
        if (ending) runCatching { session?.let { event(it, "session_finished") } }
        val edit = prefs(this).edit().putBoolean("recording", false).putString("status", finalStatus)
        if (ending) edit.remove("session")
        edit.commit()
        engine = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        status = finalStatus
        busy = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (error != null || (stopReason != null && stopReason !in listOf("已暂停", "本次记录已结束"))) {
            getSystemService(NotificationManager::class.java).notify(2, notification(finalStatus, false))
        }
        stopSelf()
    }
    private fun log(type: String, fields: Map<String, Any>) {
        try { session?.let { event(it, type, fields) } }
        catch (e: Exception) { requestStop("日志写入失败，已停止") }
    }
    override fun configured(fields: Map<String, Any>) {
        session?.let { event(it, "audio_configuration", fields) }
    }
    override fun level(db: Double) {
        Companion.db = db
        if (db > -55) lastLoudAt = SystemClock.elapsedRealtime()
    }
    override fun frames(count: Int) { frames += count; lastFrameAt = SystemClock.elapsedRealtime() }
    override fun silenced(value: Boolean) {
        if (value != muted) {
            muted = value
            handler.post { log("system_silenced", mapOf("value" to value)) }
        }
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "recording").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("KoeMemo").setContentText(text).setContentIntent(open)
            .setOngoing(ongoing).setOnlyAlertOnce(true).apply {
                if (ongoing) addAction(Notification.Action.Builder(null, "暂停", pause).build())
            }.build()
    }
    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        engine?.requestStop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
