package dev.watchmemory.recording.infrastructure.settings

import android.content.Context
import dev.watchmemory.recording.application.*

class SharedPreferencesSettings(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    override fun load() = ExperimentOptions(prefs.getBoolean("experiments_enabled", false),
        prefs.getString("experimental_audio_source", "MIC") ?: "MIC", prefs.getInt("experimental_sample_rate", 16000),
        prefs.getString("experimental_encoding", "AAC") ?: "AAC", prefs.getInt("bitrate_AAC", 48000), prefs.getInt("bitrate_OPUS", 24000), prefs.getBoolean("performance_diagnostics", false),
        prefs.getString("capture_backend", null), prefs.getBoolean("direct_pcm", false), prefs.getBoolean("reuse_ogg", false))
    override fun save(value: ExperimentOptions) {
        prefs.edit().putBoolean("experiments_enabled", value.enabled).putString("experimental_audio_source", value.source)
            .putInt("experimental_sample_rate", value.sampleRate).putString("experimental_encoding", value.encoding)
            .putInt("bitrate_AAC", value.aacBitrate).putInt("bitrate_OPUS", value.opusBitrate).putBoolean("performance_diagnostics", value.diagnostics)
            .putString("capture_backend", value.backend).putBoolean("direct_pcm", value.directPcm)
            .putBoolean("reuse_ogg", value.reuseOgg).apply()
    }
}
