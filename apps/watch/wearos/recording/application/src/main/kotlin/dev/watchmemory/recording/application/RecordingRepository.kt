package dev.watchmemory.recording.application

import dev.watchmemory.recording.domain.SessionId

data class SavedRecording(val sessionId: String?, val recording: Boolean, val status: String)
data class RecordingLibrary(val count: Int, val bytes: Long, val latestPath: String?)
interface RecordingRepository {
    fun load(): SavedRecording
    fun save(value: SavedRecording)
    fun createSession(): SessionId
    fun prepare(id: SessionId)
    fun append(id: SessionId, event: String, fields: Map<String, Any> = emptyMap())
    fun library(): RecordingLibrary
}
