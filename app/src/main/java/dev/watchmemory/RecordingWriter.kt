package dev.watchmemory

import android.media.MediaFormat
import java.io.Closeable

interface RecordingWriter : Closeable {
    fun configure(format: MediaFormat) {}
    fun write(frame: ByteArray, ptsUs: Long, length: Int = frame.size)
    fun sync()
}
