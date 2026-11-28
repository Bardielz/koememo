package dev.watchmemory.recording.infrastructure.settings

import android.content.Context
import dev.watchmemory.recording.application.*

class SharedPreferencesSettings(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    override fun load() = ExperimentOptions(prefs.getBoolean("experiments_enabled", false),
        prefs.getString("experimental_audio_source", "MIC") ?: "MIC", prefs.getInt("experimental_sample_rate", 16000),
        prefs.getString("experimental_encoding", "AAC") ?: "AAC", prefs.getInt("bitrate_AAC", 48000), prefs.getInt("bitrate_OPUS", 24000), prefs.getBoolean("performance_diagnostics", false),
        prefs.getString("capture_backend", null), sensorTimeline = prefs.getBoolean("sensor_timeline", false),
        directPcm = prefs.getBoolean("direct_pcm", false),
        reuseOgg = prefs.getBoolean("reuse_ogg", false), noiseSuppression = prefs.getBoolean("noise_suppression", true),
        quietGate = prefs.getBoolean("quiet_gate", false))
    override fun save(value: ExperimentOptions) {
        prefs.edit().putBoolean("experiments_enabled", value.enabled).putString("experimental_audio_source", value.source)
            .putInt("experimental_sample_rate", value.sampleRate).putString("experimental_encoding", value.encoding)
            .putInt("bitrate_AAC", value.aacBitrate).putInt("bitrate_OPUS", value.opusBitrate).putBoolean("performance_diagnostics", value.diagnostics)
            .putBoolean("sensor_timeline", value.sensorTimeline).putString("capture_backend", value.backend).putBoolean("direct_pcm", value.directPcm)
            .putBoolean("reuse_ogg", value.reuseOgg).putBoolean("noise_suppression", value.noiseSuppression)
            .putBoolean("quiet_gate", value.quietGate).apply()
    }
}
