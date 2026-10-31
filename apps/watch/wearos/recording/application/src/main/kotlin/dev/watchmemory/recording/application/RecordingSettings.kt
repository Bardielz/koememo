package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.*

data class ExperimentOptions(val enabled: Boolean = false, val source: String = "MIC", val sampleRate: Int = 16000,
    val encoding: String = "AAC", val aacBitrate: Int = 48000, val opusBitrate: Int = 24000, val diagnostics: Boolean = false)
interface SettingsRepository { fun load(): ExperimentOptions; fun save(value: ExperimentOptions) }
class RecordingSettings(private val repository: SettingsRepository, private val state: RecordingReadModel, val rawSupported: Boolean) {
    val experimentsEnabled get() = repository.load().enabled
    val diagnosticsEnabled get() = repository.load().let { it.enabled && it.diagnostics }
    val selectedDiagnostics get() = repository.load().diagnostics
    val selectedSource get() = repository.load().source
    val selectedSampleRate get() = repository.load().sampleRate
    val selectedEncoding get() = RecordingEncoding.effective(true, repository.load().encoding)
    val selectedBitrate get() = repository.load().let { if (selectedEncoding == RecordingEncoding.AAC) it.aacBitrate else it.opusBitrate }
    fun configuration(): RecordingConfiguration {
        val options = repository.load()
        val encoding = RecordingEncoding.effective(options.enabled, options.encoding)
        return RecordingConfiguration(ExperimentPolicy.source(options.enabled, options.source, rawSupported),
            ExperimentPolicy.sampleRate(options.enabled, options.sampleRate), encoding,
            RecordingEncoding.bitrate(options.enabled, encoding, if (encoding == RecordingEncoding.AAC) options.aacBitrate else options.opusBitrate), options.enabled)
    }
    private fun change(experimental: Boolean = true, update: (ExperimentOptions) -> ExperimentOptions) {
        check(!state.busy) { "请先暂停录音" }
        val options = repository.load()
        check(!experimental || options.enabled) { "请先开启实验功能" }
        repository.save(update(options))
    }
    fun setExperiments(value: Boolean) = change(false) { it.copy(enabled = value) }
    fun setDiagnostics(value: Boolean) = change { it.copy(diagnostics = value) }
    fun setSource(value: RecordingSource) = change { ExperimentPolicy.source(true, value.name, rawSupported); it.copy(source = value.name) }
    fun setSampleRate(value: Int) = change { check(value in ExperimentPolicy.sampleRates); it.copy(sampleRate = value) }
    fun setEncoding(value: RecordingEncoding) = change { it.copy(encoding = value.name) }
    fun setBitrate(value: Int) = change {
        check(value in selectedEncoding.rates)
        if (selectedEncoding == RecordingEncoding.AAC) it.copy(aacBitrate = value) else it.copy(opusBitrate = value)
    }
}
