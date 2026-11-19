package dev.watchmemory.recording.application

class RecordingFailure(val stage: String, cause: Exception) : Exception(cause.message, cause)
inline fun <T> recordingStage(stage: String, block: () -> T): T {
    try { return block() }
    catch (e: RecordingFailure) { throw e }
    catch (e: Exception) { throw RecordingFailure(stage, e) }
}
object FailureEvidence {
    fun fields(error: Exception, fallbackStage: String): Map<String, Any> {
        val underlying = if (error is RecordingFailure) error.cause ?: error else error
        val message = underlying.message?.trim()?.takeIf { it.isNotEmpty() } ?: underlying.javaClass.simpleName
        val trace = StringBuilder()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 8 && seen.add(current)) {
            trace.append(current.javaClass.name).append(": ").append(current.message.orEmpty().take(1024)).append('\n')
            current.stackTrace.take(24).forEach { trace.append("  at ").append(it).append('\n') }
            current.suppressed.take(4).forEach { suppressed ->
                trace.append("Suppressed: ").append(suppressed.javaClass.name).append(": ").append(suppressed.message.orEmpty().take(512)).append('\n')
                suppressed.stackTrace.take(8).forEach { trace.append("  at ").append(it).append('\n') }
            }
            current = current.cause
        }
        return mapOf("failure_stage" to (if (error is RecordingFailure) error.stage else fallbackStage),
            "exception_type" to underlying.javaClass.name, "exception_message" to message.take(1024),
            "exception_trace" to trace.toString().take(16000), "trace_bounded" to true)
    }
    fun message(error: Exception): String = fields(error, "unknown")["exception_message"] as String
}
