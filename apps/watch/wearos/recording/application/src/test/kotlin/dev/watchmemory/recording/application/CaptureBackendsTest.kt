package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.*
import org.junit.Assert.*
import org.junit.Test

class CaptureBackendsTest {
    private val device = WatchDevice("test", "watch", 35)
    private val calls = mutableListOf<String>()
    private val observer = object : CaptureObserver {
        override fun configured(fields: Map<String, Any>) {}
        override fun silenced(value: Boolean) {}
        override fun frames(count: Int) {}
        override fun level(db: Double) {}
    }
    private val writer = object : RecordingWriter {
        override fun write(frame: ByteArray, ptsUs: Long, length: Int) {}
        override fun sync() {}
        override fun close() {}
    }
    private val clock = object : DiagnosticClock {
        override fun wallNanos() = 0L
        override fun threadCpuNanos() = 0L
    }
    private val trace = object : DiagnosticTrace {
        override fun begin(name: String) {}
        override fun end() {}
    }
    private fun request(rate: Int) = CaptureRequest(
        RecordingConfiguration(RecordingSource.MIC, rate, RecordingEncoding.AAC, 48000, true),
        observer, RecordingDiagnostics(false, clock, trace), {}, { false }
    )
    private fun provider(name: String, model: String = "watch", rate: Int? = null,
                         failure: Exception? = null, emit: Boolean = false) = object : CaptureBackend {
        override val id = name
        override fun matches(device: WatchDevice) = device.model == model
        override fun supports(configuration: RecordingConfiguration) = rate == null || configuration.sampleRate == rate
        override fun record(request: CaptureRequest, writer: RecordingWriter) {
            if (emit) writer.write(byteArrayOf(1), 0)
            calls += name
            if (failure != null) throw failure
        }
    }

    @Test fun selectsPerDeviceAndPerSession() {
        val backends = CaptureBackends(device, listOf(provider("wrong", model = "other"),
            provider("vendor", rate = 16000), provider("android")))
        backends.record(request(16000), writer)
        backends.record(request(48000), writer)
        assertEquals(listOf("vendor", "android"), calls)
    }

    @Test fun unavailableBackendFallsBackButRuntimeFailureEndsSession() {
        val backends = CaptureBackends(device, listOf(provider("unavailable", failure = BackendUnavailable("missing")),
            provider("broken", failure = IllegalStateException("lost input")), provider("unused")))
        assertThrows(IllegalStateException::class.java) { backends.record(request(16000), writer) }
        assertEquals(listOf("unavailable", "broken"), calls)
    }

    @Test fun explicitSelectionCannotSilentlyUseAnotherBackend() {
        val backends = CaptureBackends(device, listOf(provider("vendor"), provider("android")))
        backends.record(request(16000).copy(requiredBackend = "android"), writer)
        assertEquals(listOf("android"), calls)
    }

    @Test fun noRetryAfterWriterReceivesOutput() {
        val backends = CaptureBackends(device, listOf(
            provider("vendor", failure = BackendUnavailable("driver lost"), emit = true), provider("android")))
        assertThrows(BackendUnavailable::class.java) { backends.record(request(16000), writer) }
        assertEquals(listOf("vendor"), calls)
    }
}
