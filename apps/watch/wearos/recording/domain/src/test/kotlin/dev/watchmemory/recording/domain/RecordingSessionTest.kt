package dev.watchmemory.recording.domain

import org.junit.Assert.*
import org.junit.Test

class RecordingSessionTest {
    @Test fun finishDuringSaveCannotBeDowngradedToPause() {
        val session = RecordingSession(SessionId("test"))
        session.begin("run"); session.requestStop(); session.requestStop(true); session.requestStop(false)
        session.complete()
        assertEquals(RunPhase.FINISHED, session.phase)
        assertNull(session.runId)
        assertThrows(IllegalStateException::class.java) { session.begin("another") }
    }
    @Test fun secondStartIsRejected() {
        val session = RecordingSession(SessionId("test")); session.begin("run")
        assertThrows(IllegalStateException::class.java) { session.begin("another") }
    }
    @Test fun safetyThresholdsAndUnknownBattery() {
        val space = RecordingSafety.MIN_FREE_BYTES
        assertNull(RecordingSafety.stop(DeviceResources(9, space), 30000))
        assertNull(RecordingSafety.stop(DeviceResources(-1, space)))
        assertEquals(SafetyStop.LOW_BATTERY, RecordingSafety.stop(DeviceResources(8, space)))
        assertEquals(SafetyStop.LOW_STORAGE, RecordingSafety.stop(DeviceResources(100, space - 1)))
        assertEquals(SafetyStop.NO_AUDIO, RecordingSafety.stop(DeviceResources(100, space), 30001))
    }
}
