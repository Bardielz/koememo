package dev.watchmemory.recording.infrastructure.audio

import android.media.MediaFormat
import android.media.MediaRecorder
import dev.watchmemory.recording.application.EncodedAudioFormat
import dev.watchmemory.recording.domain.*

object AudioFormats {
    @JvmStatic fun sourceId(value: RecordingSource) = when (value) {
        RecordingSource.MIC -> MediaRecorder.AudioSource.MIC
        RecordingSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        RecordingSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
    }
    @JvmStatic fun mime(value: RecordingEncoding) = if (value == RecordingEncoding.AAC) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_OPUS
    @JvmStatic fun encoded(format: MediaFormat): EncodedAudioFormat {
        val data = format.getByteBuffer("csd-0")?.duplicate()?.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
        return EncodedAudioFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), data)
    }
}
