package dev.watchmemory.recording.domain

@JvmInline value class SessionId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid session id" } }
}
enum class RunPhase { IDLE, RECORDING, SAVING, FINISHED }

/** A session owns at most one active capture run; paused sessions can resume. */
class RecordingSession(val id: SessionId) {
    var phase = RunPhase.IDLE; private set
    var runId: String? = null; private set
    private var finishRequested = false
    fun begin(runId: String) {
        check(phase == RunPhase.IDLE) { "Session is not ready to record" }
        require(runId.isNotBlank())
        this.runId = runId; phase = RunPhase.RECORDING
    }
    fun requestStop(finish: Boolean = false) {
        check(phase == RunPhase.RECORDING || phase == RunPhase.SAVING)
        finishRequested = finishRequested || finish; phase = RunPhase.SAVING
    }
    fun complete() {
        check(phase == RunPhase.RECORDING || phase == RunPhase.SAVING)
        phase = if (finishRequested) RunPhase.FINISHED else RunPhase.IDLE
        runId = null
    }
    fun finishIdle() { check(phase == RunPhase.IDLE); phase = RunPhase.FINISHED }
}
