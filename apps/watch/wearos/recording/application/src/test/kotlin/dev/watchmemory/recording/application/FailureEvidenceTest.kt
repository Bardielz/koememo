package dev.watchmemory.recording.application

import org.junit.Assert.*
import org.junit.Test

class FailureEvidenceTest {
    @Test fun blankMessageFallsBackToTypeAndRetainsStageCauseAndSuppressed() {
        val cause = IllegalStateException("  ")
        cause.addSuppressed(IllegalArgumentException("close failed"))
        val wrapped = RecordingFailure("CAPTURE_READ", cause)
        val fields = FailureEvidence.fields(wrapped, "unknown")
        assertEquals("IllegalStateException", fields["exception_message"])
        assertEquals("CAPTURE_READ", fields["failure_stage"])
        assertTrue((fields["exception_trace"] as String).contains("close failed"))
        assertTrue((fields["exception_trace"] as String).contains("IllegalStateException"))
    }
    @Test fun innermostStageSurvivesOuterWrapper() {
        val error = assertThrows(RecordingFailure::class.java) {
            recordingStage("OUTER") { recordingStage("INNER") { throw IllegalStateException() } }
        }
        assertEquals("INNER", error.stage)
    }
    @Test fun evidenceIsBounded() {
        val fields = FailureEvidence.fields(IllegalStateException("x".repeat(30000)), "STARTUP")
        assertTrue((fields["exception_trace"] as String).length <= 16000)
        assertTrue((fields["exception_message"] as String).length <= 1024)
    }
    @Test fun disabledDiagnosticsStillLabelFailuresWithoutClockReads() {
        val clock = object : DiagnosticClock {
            override fun wallNanos(): Long = error("Clock must not be read")
            override fun threadCpuNanos(): Long = error("Clock must not be read")
        }
        val trace = object : DiagnosticTrace {
            override fun begin(name: String) { error("Trace must not be started") }
            override fun end() { error("Trace must not be ended") }
        }
        val error = assertThrows(RecordingFailure::class.java) {
            RecordingDiagnostics(false, clock, trace).measure(RecordingStage.CAPTURE_READ) { throw IllegalStateException() }
        }
        assertEquals("CAPTURE_READ", error.stage)
    }
}
