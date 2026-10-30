package dev.watchmemory.recording.domain

data class RecordingConfiguration(
    val source: RecordingSource,
    val sampleRate: Int,
    val encoding: RecordingEncoding,
    val bitrate: Int,
    val experimentsEnabled: Boolean
) {
    init {
        require(sampleRate in ExperimentPolicy.sampleRates)
        require(bitrate in encoding.rates)
        require(experimentsEnabled || (source == RecordingSource.MIC && sampleRate == 16000 && encoding == RecordingEncoding.AAC && bitrate == 48000))
    }
}

data class DeviceResources(val batteryPercent: Int, val freeBytes: Long)
enum class SafetyStop { LOW_STORAGE, LOW_BATTERY, NO_AUDIO }
object RecordingSafety {
    const val MIN_FREE_BYTES = 256L * 1024 * 1024
    fun stop(resources: DeviceResources, idleMs: Long = 0): SafetyStop? = when {
        resources.freeBytes < MIN_FREE_BYTES -> SafetyStop.LOW_STORAGE
        resources.batteryPercent in 0..8 -> SafetyStop.LOW_BATTERY
        idleMs > 30000 -> SafetyStop.NO_AUDIO
        else -> null
    }
}
