package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.RecordingConfiguration

data class WatchDevice(val manufacturer: String, val model: String, val sdk: Int)

interface CaptureObserver {
    fun configured(fields: Map<String, Any>)
    fun level(db: Double)
    fun frames(count: Int)
    fun quietProgress() {}
    fun quietTransition(action: String, capturedSamples: Long, heldSamples: Long, replaySamples: Int) {}
    fun silenced(value: Boolean)
}

data class CaptureRequest(
    val configuration: RecordingConfiguration,
    val observer: CaptureObserver,
    val diagnostics: RecordingDiagnostics,
    val reportDiagnostics: () -> Unit,
    val stopping: () -> Boolean,
    val requiredBackend: String? = null
)

/** A provider may deliver encoded packets directly, or compose PCM capture and encoding. */
interface CaptureBackend {
    val id: String
    fun matches(device: WatchDevice): Boolean
    fun supports(configuration: RecordingConfiguration): Boolean
    /** Throw BackendUnavailable only before configuring/writing the output, after releasing resources. */
    fun record(request: CaptureRequest, writer: RecordingWriter)
}

class BackendUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Providers are registered in preference order, with the Android implementation last. */
class CaptureBackends(device: WatchDevice, providers: List<CaptureBackend>) {
    private val candidates = providers.filter { it.matches(device) }

    fun availableIds(configuration: RecordingConfiguration) =
        candidates.filter { it.supports(configuration) }.map { it.id }

    fun record(request: CaptureRequest, writer: RecordingWriter) {
        var unavailable: BackendUnavailable? = null
        for (provider in candidates) {
            if (request.stopping()) return
            if (request.requiredBackend != null && request.requiredBackend != provider.id) continue
            if (!provider.supports(request.configuration)) continue
            request.observer.configured(mapOf("capture_backend_attempt" to provider.id))
            val output = SessionOutput(writer)
            try {
                provider.record(request, output)
                return
            } catch (e: BackendUnavailable) {
                if (output.started || request.requiredBackend != null) throw e
                unavailable = e
                request.observer.configured(mapOf("capture_backend_unavailable" to provider.id,
                    "reason" to e.message.orEmpty()))
            }
        }
        throw unavailable ?: IllegalStateException("没有支持当前录音配置的后端")
    }
}

/** Once a format or packet reaches storage, switching streams could corrupt the segment. */
private class SessionOutput(private val writer: RecordingWriter) : RecordingWriter by writer {
    var started = false
        private set
    override fun configure(format: EncodedAudioFormat) {
        started = true
        writer.configure(format)
    }
    override fun write(frame: ByteArray, ptsUs: Long, length: Int) {
        started = true
        writer.write(frame, ptsUs, length)
    }
}
