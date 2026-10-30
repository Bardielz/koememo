package dev.watchmemory.recording.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentPolicyTest {
    @Test fun sampleRateObeysMasterSwitchAndRejectsStaleValues() {
        for (rate in listOf(16000, 24000, 48000)) {
            assertEquals(16000, ExperimentPolicy.sampleRate(false, rate))
            assertEquals(rate, ExperimentPolicy.sampleRate(true, rate))
        }
        assertEquals(16000, ExperimentPolicy.sampleRate(true, 44100))
    }

    @Test fun disabledAlwaysUsesMicEvenWithStaleUnsupportedSource() {
        assertEquals(RecordingSource.MIC, ExperimentPolicy.source(false, "UNPROCESSED", false))
        assertEquals(RecordingSource.MIC, ExperimentPolicy.source(false, "VOICE_RECOGNITION", true))
    }
    @Test fun enabledSelectsRequestedSource() {
        assertEquals(RecordingSource.VOICE_RECOGNITION, ExperimentPolicy.source(true, "VOICE_RECOGNITION", false))
        assertEquals(RecordingSource.UNPROCESSED, ExperimentPolicy.source(true, "UNPROCESSED", true))
    }
    @Test(expected = IllegalStateException::class) fun rawCannotSilentlyFallBack() {
        ExperimentPolicy.source(true, "UNPROCESSED", false)
    }
    @Test fun missingOrUnknownSelectionDefaultsToMic() {
        assertEquals(RecordingSource.MIC, ExperimentPolicy.source(true, null, true))
        assertEquals(RecordingSource.MIC, ExperimentPolicy.source(true, "unknown", true))
    }
}
