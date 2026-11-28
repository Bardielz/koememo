package dev.watchmemory.recording.infrastructure.android

import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import dev.watchmemory.surfaces.openRecorder
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import dev.watchmemory.R
import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.recording.domain.*
import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.infrastructure.storage.*

/** Android lifecycle adapter: foreground notification, wake lock, worker and timer ownership. */
open class RecordingService : Service(), CaptureObserver {
    private val graph by lazy { AppGraph.get(this) }
    private val runtime get() = graph.runtime
    private val sessions get() = graph.sessions
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    private var sensorLog: RecordingSensors? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakePlan: RecordingPlan? = null
    private var persistWakeEvents = false
    private var acquiredAt: Long? = null
    private val wakeTimeoutMs = 30_000L
    private var failureFields: Map<String, Any> = emptyMap()
    private var ending = false
    private var stopReason: String? = null
    @Volatile private var lastLoudAt = 0L
    @Volatile private var quietPaused = false
    @Volatile private var lastQuietReadAt = 0L
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
                    quietPaused -> "静音中，正在监听"
                    now - runtime.lastFrameAt > 5000 -> "等待音频数据"
                    now - lastLoudAt > 30000 -> "声音很轻，请检查收音"
                    else -> "正在录音"
                }
                RecordingSafety.stop(resourcesState, now - maxOf(runtime.lastFrameAt, lastQuietReadAt))?.let { requestStop(stopText(it)) }
            }
            if (now - lastTelemetry >= 60000) {
                log("telemetry", mapOf("battery_percent" to resourcesState.batteryPercent, "dbfs" to runtime.db,
                    "frames" to runtime.frames, "system_silenced" to runtime.muted, "free_bytes" to resourcesState.freeBytes,
                    "thermal_status" to getSystemService(PowerManager::class.java).currentThermalStatus) + graph.telemetry.snapshot())
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
            RecordingCommand.MARK.name -> if (runtime.busy && stopReason == null) log("marker", mapOf("marker_boottime_ns" to SystemClock.elapsedRealtimeNanos(), "run_ms" to (SystemClock.elapsedRealtime() - runtime.startedAt)))
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
        failureFields = emptyMap()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtime.status = "需要麦克风权限"
            graph.repository.save(sessions.saved().copy(status = runtime.status))
            stopSelf(); return
        }
        try {
            val config = graph.settings.configuration()
            val heartGranted = config.sensorTimeline && checkSelfPermission(RecordingSensors.heartPermission) == PackageManager.PERMISSION_GRANTED
            val micType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            var enableHeart = heartGranted
            if (heartGranted && Build.VERSION.SDK_INT >= 34) {
                try { startForeground(1, notification("正在启动录音"), micType or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) }
                catch (_: SecurityException) {
                    enableHeart = false
                    startForeground(1, notification("正在启动录音"), micType)
                }
            } else startForeground(1, notification("正在启动录音"), micType)
            resourcesState = readResources()
            RecordingSafety.stop(resourcesState)?.let { error(stopText(it)) }
            val diagnosticEnabled = graph.settings.diagnosticsEnabled
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val plan = sessions.begin(config, mapOf("battery_percent" to resourcesState.batteryPercent,
                "unprocessed_supported" to graph.settings.rawSupported, "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT, "abis" to Build.SUPPORTED_ABIS.joinToString(),
                "app_version" to (packageInfo.versionName ?: "unknown"), "app_version_code" to packageInfo.longVersionCode,
                "diagnostics_enabled" to diagnosticEnabled) + graph.telemetry.snapshot())
            lastResources = SystemClock.elapsedRealtime(); lastTelemetry = lastResources
            runtime.startedAt = SystemClock.elapsedRealtime(); runtime.lastFrameAt = runtime.startedAt
            quietPaused = false; lastQuietReadAt = 0L
            lastLoudAt = runtime.startedAt; runtime.frames = 0; runtime.muted = false
            ending = false; stopReason = null; runtime.status = "正在启动录音"; runtime.busy = true
            if (config.sensorTimeline) {
                sensorLog = RecordingSensors(this) { event, fields ->
                    graph.repository.append(plan.sessionId, event, fields + ("run" to plan.runId))
                }.also { it.start(enableHeart) }
            }
            wakePlan = plan
            persistWakeEvents = diagnosticEnabled
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "watchmemory:recording").apply { setReferenceCounted(false) }
            acquireWakeLock("startup")
            val diagnostics = RecordingDiagnostics(diagnosticEnabled, AndroidDiagnosticClock, AndroidDiagnosticTrace)
            fun reportDiagnostics(final: Boolean) {
                if (diagnosticEnabled) sessions.recordEvent("performance", diagnostics.snapshot() + ("final" to final) + ("run" to plan.runId))
            }
            stopping = false
            val request = CaptureRequest(config, this, diagnostics, { reportDiagnostics(false) }, { stopping }, graph.settings.requiredBackend)
            handler.post(monitor)
            Thread({
                var failure: String? = null
                try {
                    val writer = recordingStage("WRITER_CREATE") { graph.writerFactory.create(plan) }
                    DiagnosticRecordingWriter(writer, diagnostics).use { graph.captureBackends.record(request, it) }
                }
                catch (e: Exception) { failureFields = errorEvidence(e, "CAPTURE_SETUP_OR_CLEANUP"); failure = "录音异常：${FailureEvidence.message(e)}" }
                try { reportDiagnostics(true) } catch (e: Exception) {
                    if (failure == null) { failureFields = errorEvidence(e, "DIAGNOSTIC_LOG"); failure = "诊断日志写入失败：${FailureEvidence.message(e)}" }
                }
                val result = failure; handler.post { complete(result) }
            }, "audio-capture").start()
        } catch (e: Exception) { failureFields = errorEvidence(e, "STARTUP"); complete("启动失败：${FailureEvidence.message(e)}") }
    }
    private fun errorEvidence(error: Exception, stage: String): Map<String, Any> {
        android.util.Log.e("KoeMemo", "Recording failure: $stage", error)
        val fields = FailureEvidence.fields(error, stage).toMutableMap()
        var current: Throwable? = error
        repeat(8) {
            val item = current ?: return@repeat
            if (item is android.media.MediaCodec.CodecException) {
                fields["codec_diagnostic_info"] = item.diagnosticInfo
                fields["codec_error_code"] = item.errorCode
                fields["codec_recoverable"] = item.isRecoverable
                fields["codec_transient"] = item.isTransient
            }
            current = item.cause
        }
        return fields
    }
    // All lock transitions and observations run on the service main thread.
    private fun wakeEvent(action: String, reason: String, now: Long, since: Long?) {
        val plan = wakePlan ?: return
        val fields = mutableMapOf<String, Any>("run" to plan.runId, "action" to action,
            "reason" to reason, "timeout_ms" to wakeTimeoutMs, "observed_elapsed_ms" to now)
        since?.let { fields["since_acquire_ms"] = now - it }
        android.util.Log.d("KoeMemoWakeLock", org.json.JSONObject(fields as Map<*, *>).toString())
        if (persistWakeEvents) try {
            graph.repository.append(plan.sessionId, "wake_lock", fields)
        } catch (e: Exception) {
            android.util.Log.w("KoeMemoWakeLock", "Could not persist wake_lock event", e)
        }
    }

    private fun acquireWakeLock(reason: String) {
        val lock = wakeLock ?: return
        val now = SystemClock.elapsedRealtime()
        val held = lock.isHeld
        val previous = acquiredAt
        if (!held && previous != null) wakeEvent("observed_unheld", reason, now, previous)
        lock.acquire(wakeTimeoutMs)
        acquiredAt = now
        wakeEvent(if (held) "renew" else "acquire", reason, now, previous)
    }

    private fun releaseWakeLock(reason: String) {
        val lock = wakeLock ?: return
        val previous = acquiredAt ?: return
        val now = SystemClock.elapsedRealtime()
        val held = lock.isHeld
        if (held) lock.release()
        acquiredAt = null
        wakeEvent(if (held) "release" else "observed_unheld", reason, now, previous)
    }

    private fun requestStop(reason: String) {
        if (stopReason != null) return
        acquireWakeLock(reason)
        sessions.requestStop(ending)
        stopReason = reason; runtime.status = "正在保存…"; stopping = true
    }
    private fun complete(error: String?) {
        sensorLog?.close(); sensorLog = null
        handler.removeCallbacks(monitor)
        var finalStatus = error ?: stopReason ?: "录音已停止"
        try {
            sessions.complete(finalStatus, mapOf("duration_ms" to (if (runtime.busy) SystemClock.elapsedRealtime() - runtime.startedAt else 0),
                "frames" to runtime.frames, "battery_percent" to readResources().batteryPercent) + graph.telemetry.snapshot() + failureFields, error != null, ending)
        } catch (e: Exception) { finalStatus = "保存状态失败：${e.message}" }
        finally {
            releaseWakeLock(if (error == null) "save_complete" else "recording_error")
            wakePlan = null
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
    override fun frames(count: Int) {
        if (runtime.frames == 0L) handler.post {
            // A stop already queued on the main thread owns the lock until final save.
            if (!stopping) releaseWakeLock("first_frames")
        }
        runtime.frames += count
        runtime.lastFrameAt = SystemClock.elapsedRealtime()
    }
    override fun quietProgress() { lastQuietReadAt = SystemClock.elapsedRealtime() }
    override fun quietTransition(action: String, capturedSamples: Long, heldSamples: Long, replaySamples: Int) {
        quietPaused = action == "pause"
        sessions.recordEvent("quiet_gate", mapOf("action" to action, "captured_samples" to capturedSamples,
            "held_samples" to heldSamples, "replay_samples" to replaySamples))
    }
    override fun silenced(value: Boolean) {
        if (value != runtime.muted) { runtime.muted = value; handler.post { log("system_silenced", mapOf("value" to value)) } }
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = openRecorder(this)
        val pause = PendingIntent.getService(this, 1, Intent(this, dev.watchmemory.RecordingService::class.java)
            .setAction(RecordingCommand.PAUSE.name), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "recording").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("KoeMemo").setContentText(text).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).apply {
                if (ongoing) addAction(R.drawable.ic_mic, "暂停", pause)
            }
        if (ongoing) OngoingActivity.Builder(this, 1, builder)
            .setStaticIcon(R.drawable.ic_mic).setTouchIntent(open).build().apply(this)
        return builder.build()
    }
    override fun onDestroy() {
        sensorLog?.close(); sensorLog = null
        handler.removeCallbacks(monitor); stopping = true
        releaseWakeLock("service_destroy")
        super.onDestroy()
    }
}
