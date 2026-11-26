package dev.watchmemory.recording.infrastructure.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import dev.watchmemory.recording.domain.RecordingSource

internal class SessionNoiseSuppression {
    private var effect: NoiseSuppressor? = null

    fun configure(session: Int?, source: RecordingSource, requested: Boolean): Map<String, Any> {
        val fields = mutableMapOf<String, Any>("ns_requested" to requested)
        fields["ns_status"] = try {
            when {
                source == RecordingSource.UNPROCESSED -> "raw_source"
                session == null || session <= 0 -> "no_audio_session"
                !NoiseSuppressor.isAvailable() -> "unavailable"
                else -> {
                    val engine = NoiseSuppressor.create(session)
                    effect = engine
                    if (engine == null) "create_failed" else {
                        fields["ns_engine"] = engine.descriptor.name
                        if (engine.enabled != requested && engine.hasControl()) {
                            fields["ns_set_result"] = engine.setEnabled(requested)
                        }
                        val enabled = engine.enabled
                        fields["ns_enabled"] = enabled
                        when {
                            enabled == requested -> if (enabled) "enabled" else "disabled"
                            !engine.hasControl() -> "no_control"
                            fields["ns_set_result"] != AudioEffect.SUCCESS -> "set_failed"
                            else -> "state_mismatch"
                        }
                    }
                }
            }
        } catch (error: RuntimeException) {
            fields["ns_error"] = error.javaClass.simpleName
            release()
            "error"
        }
        return fields
    }

    fun release() {
        try {
            effect?.release()
        } catch (error: RuntimeException) {
            Log.w("KoeMemo", "Could not release session noise suppressor", error)
        } finally {
            effect = null
        }
    }
}
