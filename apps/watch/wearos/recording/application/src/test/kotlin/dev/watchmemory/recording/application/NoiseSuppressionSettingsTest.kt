package dev.watchmemory.recording.application

import org.junit.Assert.*
import org.junit.Test

class NoiseSuppressionSettingsTest {
    @Test fun defaultAndUserPreferenceApplyIndependentlyOfExperiments() {
        val repository = object : SettingsRepository {
            var options = ExperimentOptions()
            override fun load() = options
            override fun save(value: ExperimentOptions) { options = value }
        }
        val state = RecordingRuntime()
        val settings = RecordingSettings(repository, state, false)
        assertTrue(settings.configuration().noiseSuppression)
        settings.setNoiseSuppression(false)
        settings.setExperiments(true)
        assertFalse(settings.configuration().noiseSuppression)
        state.busy = true
        assertThrows(IllegalStateException::class.java) { settings.setNoiseSuppression(true) }
        assertFalse(settings.configuration().noiseSuppression)
    }
}
