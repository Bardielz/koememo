package dev.watchmemory.recording.application

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSettingsTest {
    @Test fun diagnosticsRequireMasterSwitchAndIdleStateButKeepPreference() {
        val repo = object : SettingsRepository {
            var value = ExperimentOptions()
            override fun load() = value
            override fun save(value: ExperimentOptions) { this.value = value }
        }
        val state = RecordingRuntime(); val settings = RecordingSettings(repo, state, false)
        val defaults = settings.configuration()
        assertFalse(settings.diagnosticsEnabled)
        assertThrows(IllegalStateException::class.java) { settings.setDiagnostics(true) }
        settings.setExperiments(true); settings.setDiagnostics(true)
        assertTrue(settings.diagnosticsEnabled)
        state.busy = true
        assertThrows(IllegalStateException::class.java) { settings.setDiagnostics(false) }
        state.busy = false; settings.setExperiments(false)
        assertFalse(settings.diagnosticsEnabled); assertTrue(settings.selectedDiagnostics)
        assertEquals(defaults, settings.configuration())
        settings.setExperiments(true); assertTrue(settings.diagnosticsEnabled)
    }
}
