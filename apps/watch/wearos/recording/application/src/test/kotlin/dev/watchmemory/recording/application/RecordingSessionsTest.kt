package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.*
import org.junit.Assert.*
import org.junit.Test

class RecordingSessionsTest {
    private class Repository : RecordingRepository {
        var value = SavedRecording(null, false, "idle")
        val events = mutableListOf<String>()
        var ids = 0
        override fun load() = value
        override fun save(value: SavedRecording) { this.value = value }
        override fun createSession() = SessionId("session-${++ids}")
        override fun prepare(id: SessionId) {}
        override fun append(id: SessionId, event: String, fields: Map<String, Any>) { events += event }
        override fun library() = RecordingLibrary(0, 0, null)
    }
    private val config = RecordingConfiguration(RecordingSource.MIC, 16000, RecordingEncoding.AAC, 48000, false)
    @Test fun pauseResumesSameSessionButFinishCreatesAnother() {
        val repo = Repository(); var run = 0
        val sessions = RecordingSessions(repo) { "run-${++run}" }
        val first = sessions.begin(config, emptyMap())
        sessions.requestStop(); sessions.complete("paused", emptyMap(), false, false)
        assertFalse(repo.value.recording)
        val second = sessions.begin(config, emptyMap())
        assertEquals(first.sessionId, second.sessionId)
        assertNotEquals(first.runId, second.runId)
        sessions.requestStop(true); sessions.complete("finished", emptyMap(), false, true)
        assertNull(repo.value.sessionId)
        assertEquals("session_finished", repo.events.last())
        assertNotEquals(first.sessionId, sessions.begin(config, emptyMap()).sessionId)
    }
    @Test fun processRecoveryIsIdempotentAndPreservesSession() {
        val repo = Repository(); repo.value = SavedRecording("session-1", true, "recording")
        val sessions = RecordingSessions(repo) { "run" }
        sessions.recoverAfterProcessDeath(); sessions.recoverAfterProcessDeath()
        assertFalse(repo.value.recording)
        assertEquals("session-1", repo.value.sessionId)
        assertEquals(listOf("unclean_restart"), repo.events)
    }
    @Test fun errorRetainsSessionForManualResume() {
        val repo = Repository(); val sessions = RecordingSessions(repo) { "run" }
        val first = sessions.begin(config, emptyMap())
        sessions.complete("encoder failed", emptyMap(), true, false)
        assertFalse(repo.value.recording)
        assertEquals(first.sessionId.value, repo.value.sessionId)
        assertEquals("error", repo.events.last())
    }
}
