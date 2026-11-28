package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.*

data class RecordingPlan(val sessionId: SessionId, val runId: String, val configuration: RecordingConfiguration)

/** Session use cases; no Android, files, JSON or worker threads. Called on the owner thread. */
class RecordingSessions(private val repository: RecordingRepository, private val newRunId: () -> String) {
    private var session: RecordingSession? = null
    fun saved() = repository.load()
    fun library() = repository.library()
    fun recoverAfterProcessDeath(evidence: () -> Map<String, Any> = { emptyMap() }) {
        val saved = repository.load()
        if (!saved.recording) return
        saved.sessionId?.let { runCatching { repository.append(SessionId(it), "unclean_restart", runCatching { evidence() }.getOrElse { mapOf("exit_history_error" to it.javaClass.name) }) } }
        repository.save(saved.copy(recording = false, status = "上次录音意外中断，请检查文件"))
    }
    fun begin(configuration: RecordingConfiguration, fields: Map<String, Any>): RecordingPlan {
        check(session == null || session!!.phase == RunPhase.IDLE) { "Already recording" }
        val id = repository.load().sessionId?.let { SessionId(it) } ?: repository.createSession()
        repository.prepare(id)
        val aggregate = RecordingSession(id)
        val run = newRunId(); aggregate.begin(run)
        session = aggregate
        repository.save(SavedRecording(id.value, true, "正在启动录音"))
        repository.append(id, "run_started", fields + mapOf("run" to run,
            "experiments_enabled" to configuration.experimentsEnabled, "audio_source" to configuration.source.name,
            "quiet_gate" to configuration.quietGate, "voice_probability" to configuration.voiceProbability,
            "sensor_timeline" to configuration.sensorTimeline,
            "sample_rate" to configuration.sampleRate, "encoding" to configuration.encoding.name, "bitrate" to configuration.bitrate))
        return RecordingPlan(id, run, configuration)
    }
    fun requestStop(finish: Boolean = false) { session?.requestStop(finish) }
    fun finishIdle() {
        val saved = repository.load()
        saved.sessionId?.let {
            val aggregate = RecordingSession(SessionId(it)); aggregate.finishIdle()
            repository.append(aggregate.id, "session_finished")
        }
        repository.save(SavedRecording(null, false, "本次记录已结束")); session = null
    }
    fun complete(status: String, fields: Map<String, Any>, error: Boolean, finish: Boolean) {
        val aggregate = session
        if (aggregate != null) {
            if (finish) aggregate.requestStop(true)
            aggregate.complete()
            runCatching { repository.append(aggregate.id, if (error) "error" else "run_stopped", fields + ("reason" to status)) }
            if (finish) runCatching { repository.append(aggregate.id, "session_finished") }
        }
        val saved = repository.load()
        if (aggregate == null && error) {
            runCatching {
                val id = saved.sessionId?.let { SessionId(it) } ?: repository.createSession()
                repository.prepare(id)
                repository.append(id, "error", fields + ("reason" to status))
            }
        }
        repository.save(SavedRecording(if (finish) null else saved.sessionId, false, status))
        session = null
    }
    fun recordEvent(event: String, fields: Map<String, Any>) { session?.let { repository.append(it.id, event, fields) } }
}
