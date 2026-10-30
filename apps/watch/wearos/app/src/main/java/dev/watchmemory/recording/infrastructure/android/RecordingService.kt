package dev.watchmemory.recording.infrastructure.android

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import dev.watchmemory.R
import dev.watchmemory.MainActivity
import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.recording.domain.*
import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.infrastructure.audio.CaptureEngine
import dev.watchmemory.recording.infrastructure.storage.*

/** Android lifecycle adapter: foreground notification, wake lock, worker and timer ownership. */
open class RecordingService : Service(), CaptureEngine.Observer {
    private val graph by lazy { AppGraph.get(this) }
    private val runtime get() = graph.runtime
    private val sessions get() = graph.sessions
    private val handler = Handler(Looper.getMainLooper())
    private var engine: CaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ending = false
    private var stopReason: String? = null
    @Volatile private var lastLoudAt = 0L
    private var lastTelemetry = 0L
    private var lastResources = 0L
    private var resourcesState = DeviceResources(-1, 0)
    private var lastNotice = ""
    private fun readResources() = DeviceResources(
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), filesDir.usableSpace)
    private fun stopText(reason: SafetyStop) = when (reason) {
        SafetyStop.LOW_STORAGE -> "剩余空间不足 256 MB，已停止"
        SafetyStop.LOW_BATTERY -> "电量低于 9%，已停止"
        SafetyStop.NO_AUDIO -> "超过 30 秒无编码数据，已停止"
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (!runtime.busy) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastResources >= 30000) { resourcesState = readResources(); lastResources = now }
            if (stopReason == null) {
                runtime.status = when {
                    runtime.muted -> "麦克风被系统静音"
                    now - runtime.lastFrameAt > 5000 -> "等待音频数据"
                    now - lastLoudAt > 30000 -> "声音很轻，请检查收音"
                    else -> "正在录音"
                }
                RecordingSafety.stop(resourcesState, now - runtime.lastFrameAt)?.let { requestStop(stopText(it)) }
            }
            if (now - lastTelemetry >= 60000) {
                if (stopReason == null) wakeLock?.acquire(180_000L)
                log("telemetry", mapOf("battery_percent" to resourcesState.batteryPercent, "dbfs" to runtime.db,
                    "frames" to runtime.frames, "system_silenced" to runtime.muted, "free_bytes" to resourcesState.freeBytes,
                    "thermal_status" to getSystemService(PowerManager::class.java).currentThermalStatus))
                lastTelemetry = now
            }
            if (runtime.status != lastNotice) {
                getSystemService(NotificationManager::class.java).notify(1, notification(runtime.status))
                lastNotice = runtime.status
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
        when (intent?.action?.uppercase(java.util.Locale.ROOT)) {
            RecordingCommand.START.name -> if (!runtime.busy) begin()
            RecordingCommand.MARK.name -> if (runtime.busy && stopReason == null) log("marker", mapOf("run_ms" to (SystemClock.elapsedRealtime() - runtime.startedAt)))
            RecordingCommand.PAUSE.name -> if (runtime.busy) requestStop("已暂停") else stopSelf()
            RecordingCommand.FINISH.name -> {
                ending = true
                if (runtime.busy) { sessions.requestStop(true); requestStop("本次记录已结束") }
                else { sessions.finishIdle(); runtime.status = "本次记录已结束"; stopSelf() }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtime.status = "需要麦克风权限"
            graph.repository.save(sessions.saved().copy(status = runtime.status))
            stopSelf(); return
        }
        try {
            startForeground(1, notification("正在启动录音"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val config = graph.settings.configuration()
            resourcesState = readResources()
            RecordingSafety.stop(resourcesState)?.let { error(stopText(it)) }
            val plan = sessions.begin(config, mapOf("battery_percent" to resourcesState.batteryPercent,
                "unprocessed_supported" to graph.settings.rawSupported, "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT, "abis" to Build.SUPPORTED_ABIS.joinToString()))
            lastResources = 0; lastTelemetry = 0
            runtime.startedAt = SystemClock.elapsedRealtime(); runtime.lastFrameAt = runtime.startedAt
            lastLoudAt = runtime.startedAt; runtime.frames = 0; runtime.muted = false
            ending = false; stopReason = null; runtime.status = "正在启动录音"; runtime.busy = true
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "watchmemory:recording").apply { setReferenceCounted(false); acquire(180_000L) }
            val capture = CaptureEngine(this, config.source, config.sampleRate, config.encoding, config.bitrate)
            engine = capture; handler.post(monitor)
            Thread({
                var failure: String? = null
                try { graph.writerFactory.create(plan).use { capture.record(it) } }
                catch (e: Exception) { failure = "录音异常：${e.message ?: e.javaClass.simpleName}" }
                val result = failure; handler.post { complete(result) }
            }, "audio-capture").start()
        } catch (e: Exception) { complete("启动失败：${e.message ?: e.javaClass.simpleName}") }
    }
    private fun requestStop(reason: String) {
        if (stopReason != null) return
        sessions.requestStop(ending)
        stopReason = reason; runtime.status = "正在保存…"; engine?.requestStop()
    }
    private fun complete(error: String?) {
        handler.removeCallbacks(monitor)
        var finalStatus = error ?: stopReason ?: "录音已停止"
        try {
            sessions.complete(finalStatus, mapOf("duration_ms" to (if (runtime.busy) SystemClock.elapsedRealtime() - runtime.startedAt else 0),
                "frames" to runtime.frames, "battery_percent" to readResources().batteryPercent), error != null, ending)
        } catch (e: Exception) { finalStatus = "保存状态失败：${e.message}" }
        finally {
            engine = null
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null; runtime.status = finalStatus; runtime.busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (error != null || (stopReason != null && stopReason !in listOf("已暂停", "本次记录已结束"))) {
            getSystemService(NotificationManager::class.java).notify(2, notification(finalStatus, false))
        }
        stopSelf()
    }
    private fun log(type: String, fields: Map<String, Any>) {
        try { sessions.recordEvent(type, fields) } catch (_: Exception) { requestStop("日志写入失败，已停止") }
    }
    override fun configured(fields: Map<String, Any>) { sessions.recordEvent("audio_configuration", fields) }
    override fun level(db: Double) { runtime.db = db; if (db > -55) lastLoudAt = SystemClock.elapsedRealtime() }
    override fun frames(count: Int) { runtime.frames += count; runtime.lastFrameAt = SystemClock.elapsedRealtime() }
    override fun silenced(value: Boolean) {
        if (value != runtime.muted) { runtime.muted = value; handler.post { log("system_silenced", mapOf("value" to value)) } }
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, dev.watchmemory.RecordingService::class.java)
            .setAction(RecordingCommand.PAUSE.name), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "recording").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("KoeMemo").setContentText(text).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).apply {
                if (ongoing) addAction(Notification.Action.Builder(null, "暂停", pause).build())
            }.build()
    }
    override fun onDestroy() {
        handler.removeCallbacks(monitor); engine?.requestStop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
