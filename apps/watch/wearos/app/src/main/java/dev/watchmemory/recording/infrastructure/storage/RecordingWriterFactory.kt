package dev.watchmemory.recording.infrastructure.storage

import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.domain.RecordingEncoding

class RecordingWriterFactory(private val repository: FileRecordingRepository) {
    fun create(plan: RecordingPlan): RecordingWriter {
        val config = plan.configuration
        val closed: (String, Long, Long, Long, String) -> Unit = { name, first, last, bytes, hash ->
            repository.append(plan.sessionId, "segment_closed", mapOf("file" to name, "first_pts_us" to first,
                "last_pts_us" to last, "bytes" to bytes, "sha256" to hash, "run" to plan.runId,
                "sample_rate" to config.sampleRate, "encoding" to config.encoding.name, "bitrate" to config.bitrate))
        }
        val directory = repository.directory(plan.sessionId)
        return if (config.encoding == RecordingEncoding.AAC)
            SegmentWriter(directory, plan.runId, sampleRate = config.sampleRate, onClosed = closed)
        else OpusSegmentWriter(directory, plan.runId, onClosed = closed)
    }
}
