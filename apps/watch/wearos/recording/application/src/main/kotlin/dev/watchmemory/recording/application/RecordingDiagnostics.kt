package dev.watchmemory.recording.application

/** All calls belong to the capture thread. CPU time excludes platform codec processes. */
interface DiagnosticClock {
    fun wallNanos(): Long
    fun threadCpuNanos(): Long
}
interface DiagnosticTrace {
    fun begin(name: String)
    fun end()
}
enum class RecordingStage(val sampleEvery: Int = 1) {
    CAPTURE_READ, LEVEL, ENCODER_INPUT(64), ENCODER_OUTPUT_WAIT(64), OUTPUT_COPY(64),
    WRITE_AND_PACKAGE, SYNC, CLOSE
}
class RecordingDiagnostics(
    @PublishedApi internal val enabled: Boolean,
    @PublishedApi internal val clock: DiagnosticClock,
    @PublishedApi internal val trace: DiagnosticTrace
) {
    @PublishedApi internal val calls = LongArray(RecordingStage.entries.size)
    @PublishedApi internal val samples = LongArray(calls.size)
    @PublishedApi internal val wall = LongArray(calls.size)
    @PublishedApi internal val cpu = LongArray(calls.size)
    @PublishedApi internal val maxWall = LongArray(calls.size)
    inline fun <T> measure(stage: RecordingStage, block: () -> T): T {
        if (!enabled) return recordingStage(stage.name) { block() }
        val i = stage.ordinal
        val previousCalls = calls[i]++
        if (stage.sampleEvery > 1 && previousCalls % stage.sampleEvery != 0L) {
            return recordingStage(stage.name) { block() }
        }
        samples[i]++
        val start = clock.wallNanos()
        val cpuStart = clock.threadCpuNanos()
        trace.begin("KoeMemo/" + stage.name)
        try { return recordingStage(stage.name) { block() } }
        finally {
            trace.end()
            val elapsed = (clock.wallNanos() - start).coerceAtLeast(0)
            val cpuElapsed = (clock.threadCpuNanos() - cpuStart).coerceAtLeast(0)
            wall[i] += elapsed; cpu[i] += cpuElapsed
            maxWall[i] = maxOf(maxWall[i], elapsed)
        }
    }
    fun snapshot(): Map<String, Any> {
        if (!enabled) return emptyMap()
        val result = linkedMapOf<String, Any>("schema" to 2, "cumulative" to true,
            "cpu_scope" to "capture_thread_only", "duration_unit" to "ns")
        RecordingStage.entries.forEach { stage ->
            val i = stage.ordinal; val key = stage.name.lowercase()
            result[key + "_calls"] = calls[i]
            result[key + "_samples"] = samples[i]
            result[key + "_sample_every"] = stage.sampleEvery
            result[key + "_wall_ns"] = wall[i]
            result[key + "_cpu_ns"] = cpu[i]
            result[key + "_max_wall_ns"] = maxWall[i]
        }
        return result
    }
}
class DiagnosticRecordingWriter(private val delegate: RecordingWriter, private val diagnostics: RecordingDiagnostics) : RecordingWriter {
    override fun configure(format: EncodedAudioFormat) = delegate.configure(format)
    override fun write(frame: ByteArray, ptsUs: Long, length: Int) = diagnostics.measure(RecordingStage.WRITE_AND_PACKAGE) {
        delegate.write(frame, ptsUs, length)
    }
    override fun sync() = diagnostics.measure(RecordingStage.SYNC) { delegate.sync() }
    override fun close() = diagnostics.measure(RecordingStage.CLOSE) { delegate.close() }
}
