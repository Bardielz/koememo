package dev.watchmemory.recording.infrastructure.audio

import java.nio.ByteBuffer
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import dev.watchmemory.recording.domain.RecordingSource

/** Mono signed 16-bit little-endian PCM. read returns a byte count. */
interface PcmInput {
    val audioSessionId: Int? get() = null
    val sampleRate: Int
    val deviceSampleRate: Int?
    val bufferSizeInFrames: Int
    val silenced: Boolean?
    fun start()
    fun read(buffer: ByteArray): Int
    fun readDirect(buffer: ByteBuffer, length: Int): Int? = null
    fun stop()
    fun release()
}

@SuppressLint("MissingPermission") // The activity and recording service check RECORD_AUDIO.
internal fun androidPcmInput(source: RecordingSource, rate: Int): PcmInput {
    val channel = AudioFormat.CHANNEL_IN_MONO
    val format = AudioFormat.ENCODING_PCM_16BIT
    val minimum = AudioRecord.getMinBufferSize(rate, channel, format)
    check(minimum > 0) { "Microphone does not support $rate Hz" }
    val mic = AudioRecord(AudioFormats.sourceId(source), rate, channel, format, maxOf(minimum * 2, rate * 2))
    if (mic.state != AudioRecord.STATE_INITIALIZED) {
        mic.release()
        error("Microphone initialization failed")
    }
    return object : PcmInput {
        override val audioSessionId get() = mic.audioSessionId
        override val sampleRate get() = mic.sampleRate
        override val deviceSampleRate get() = mic.activeRecordingConfiguration?.format?.sampleRate
        override val bufferSizeInFrames get() = mic.bufferSizeInFrames
        override val silenced get() = mic.activeRecordingConfiguration?.isClientSilenced
        override fun start() {
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        }
        override fun read(buffer: ByteArray) = mic.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        override fun readDirect(buffer: ByteBuffer, length: Int) = mic.read(buffer, length, AudioRecord.READ_BLOCKING)
        override fun stop() = mic.stop()
        override fun release() = mic.release()
    }
}
