package dev.watchmemory.recording.infrastructure.storage

import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.domain.RecordingEncoding

class RecordingWriterFactory(private val repository: FileRecordingRepository) {
    fun create(plan: RecordingPlan): RecordingWriter {
        val config = plan.configuration
        val closed: (ClosedSegment) -> Unit = { part ->
            repository.append(plan.sessionId, "segment_closed", mapOf("file" to part.name, "first_pts_us" to part.firstPtsUs,
                "last_pts_us" to part.lastPtsUs, "playback_start_us" to part.playbackStartUs, "end_pts_us" to part.endPtsUs,
                "bytes" to part.bytes, "sha256" to part.sha256, "run" to plan.runId,
                "sample_rate" to config.sampleRate, "encoding" to config.encoding.name, "bitrate" to config.bitrate))
        }
        val directory = repository.directory(plan.sessionId)
        return if (config.encoding == RecordingEncoding.AAC)
            SegmentWriter(directory, plan.runId, sampleRate = config.sampleRate, onClosed = closed)
        else OpusSegmentWriter(directory, plan.runId, reuseBuffers = config.reuseOgg, onClosed = closed)
    }
}
