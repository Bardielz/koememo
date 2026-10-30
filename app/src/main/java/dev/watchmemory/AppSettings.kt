package dev.watchmemory

import android.content.Context
import android.media.AudioManager

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val rawSupported = context.getSystemService(AudioManager::class.java)
        .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val experimentsEnabled get() = prefs.getBoolean("experiments_enabled", false)
    val selectedSource get() = prefs.getString("experimental_audio_source", "MIC")
    val selectedSampleRate get() = prefs.getInt("experimental_sample_rate", 16000)
    val selectedEncoding get() = RecordingEncoding.effective(true, prefs.getString("experimental_encoding", "AAC"))
    val selectedBitrate get() = prefs.getInt("bitrate_${selectedEncoding.name}", selectedEncoding.defaultBitrate)
    fun effectiveEncoding() = RecordingEncoding.effective(experimentsEnabled, selectedEncoding.name)
    fun effectiveBitrate() = RecordingEncoding.bitrate(experimentsEnabled, effectiveEncoding(), selectedBitrate)
    fun setEncoding(value: RecordingEncoding) {
        check(!RecordingService.busy && experimentsEnabled) { "请先暂停录音并开启实验功能" }
        prefs.edit().putString("experimental_encoding", value.name).apply()
    }
    fun setBitrate(value: Int) {
        check(!RecordingService.busy && experimentsEnabled) { "请先暂停录音并开启实验功能" }
        check(value in selectedEncoding.rates) { "无效码率" }
        prefs.edit().putInt("bitrate_${selectedEncoding.name}", value).apply()
    }
    fun effectiveSampleRate() = ExperimentPolicy.sampleRate(experimentsEnabled, selectedSampleRate)
    fun setSampleRate(rate: Int) {
        check(!RecordingService.busy) { "请先暂停录音" }
        check(experimentsEnabled) { "请先开启实验功能" }
        check(rate in ExperimentPolicy.sampleRates) { "无效的采样率" }
        prefs.edit().putInt("experimental_sample_rate", rate).apply()
    }
    fun effectiveSource() = ExperimentPolicy.source(experimentsEnabled, selectedSource, rawSupported)
    fun setExperiments(enabled: Boolean) {
        check(!RecordingService.busy) { "请先暂停录音" }
        prefs.edit().putBoolean("experiments_enabled", enabled).apply()
    }
    fun setSource(source: RecordingSource) {
        check(!RecordingService.busy) { "请先暂停录音" }
        check(experimentsEnabled) { "请先开启实验功能" }
        ExperimentPolicy.source(true, source.name, rawSupported)
        prefs.edit().putString("experimental_audio_source", source.name).apply()
    }
}
