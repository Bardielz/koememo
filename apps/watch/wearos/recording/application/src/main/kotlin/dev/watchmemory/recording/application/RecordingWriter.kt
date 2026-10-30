package dev.watchmemory.recording.application

import java.io.Closeable

/** Codec-independent application port. Android MediaFormat must stay in the adapter. */
data class EncodedAudioFormat(val sampleRate: Int, val channels: Int, val codecSpecificData: ByteArray?)
interface RecordingWriter : Closeable {
    fun configure(format: EncodedAudioFormat) {}
    fun write(frame: ByteArray, ptsUs: Long, length: Int = frame.size)
    fun sync()
}
