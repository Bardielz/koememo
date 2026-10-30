package dev.watchmemory.recording.domain

enum class RecordingSource {
    MIC, VOICE_RECOGNITION, UNPROCESSED
}

object ExperimentPolicy {
    val sampleRates = listOf(16000, 24000, 48000)
    fun sampleRate(enabled: Boolean, selected: Int): Int =
        if (enabled && selected in sampleRates) selected else 16000

    fun source(enabled: Boolean, selected: String?, rawSupported: Boolean): RecordingSource {
        if (!enabled) return RecordingSource.MIC
        val source = RecordingSource.entries.firstOrNull { it.name == selected } ?: RecordingSource.MIC
        check(source != RecordingSource.UNPROCESSED || rawSupported) { "设备不支持 UNPROCESSED，请在设置中选择其他音源" }
        return source
    }
}
