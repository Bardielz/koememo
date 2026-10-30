package dev.watchmemory.recording.application

/** Shared read model, owned by the application rather than Android Service statics. */
interface RecordingReadModel {
    val busy: Boolean
    val status: String
    val db: Double
    val muted: Boolean
    val startedAt: Long
    val frames: Long
    val lastFrameAt: Long
}
class RecordingRuntime : RecordingReadModel {
    @Volatile override var busy = false
    @Volatile override var status = "准备记录"
    @Volatile override var db = -90.0
    @Volatile override var muted = false
    @Volatile override var startedAt = 0L
    @Volatile override var frames = 0L
    @Volatile override var lastFrameAt = 0L
}
