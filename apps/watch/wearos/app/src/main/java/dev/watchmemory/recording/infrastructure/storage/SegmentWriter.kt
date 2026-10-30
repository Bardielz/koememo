package dev.watchmemory.recording.infrastructure.storage

import dev.watchmemory.recording.application.RecordingWriter
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Independent of Android: continuous encoded frames, rotated without restarting capture. */
class SegmentWriter(
    private val directory: File,
    private val prefix: String,
    private val segmentUs: Long = 300_000_000,
    private val sampleRate: Int = 16000,
    private val onClosed: (String, Long, Long, Long, String) -> Unit
) : RecordingWriter {
    private val rateIndex = when (sampleRate) {
        16000 -> 8
        24000 -> 6
        48000 -> 3
        else -> throw IllegalArgumentException("Unsupported sample rate: $sampleRate")
    }
    private var stream: FileOutputStream? = null
    private var buffered: BufferedOutputStream? = null
    private val header = ByteArray(7)
    private var firstUs = 0L
    private var lastUs = 0L
    private var bytes = 0L
    private var index = 0
    private var name = ""
    private var digest = MessageDigest.getInstance("SHA-256")
    override fun write(frame: ByteArray, ptsUs: Long, length: Int) {
        require(length in 1..frame.size && length + 7 <= 8191) { "Invalid ADTS frame length" }
        require(stream == null || ptsUs >= lastUs) { "Non-monotonic audio timestamp" }
        if (stream != null && ptsUs - firstUs >= segmentUs) close()
        if (stream == null) {
            name = "$prefix-${String.format(Locale.US, "%04d", ++index)}.aac"
            val file = File(directory, name)
            if (!file.createNewFile()) throw IOException("Refusing to overwrite $name")
            stream = FileOutputStream(file)
            buffered = BufferedOutputStream(stream!!, 32768)
            firstUs = ptsUs
            bytes = 0
            digest = MessageDigest.getInstance("SHA-256")
        }
        val size = length + 7
        // MPEG-4 AAC LC, selected frequency index, mono, one raw block per frame.
        header[0] = 0xff.toByte(); header[1] = 0xf1.toByte()
        header[2] = (0x40 or (rateIndex shl 2)).toByte()
        header[3] = (0x40 or (size shr 11)).toByte()
        header[4] = (size shr 3).toByte()
        header[5] = (((size and 7) shl 5) or 0x1f).toByte(); header[6] = 0xfc.toByte()
        buffered!!.write(header)
        buffered!!.write(frame, 0, length)
        digest.update(header)
        digest.update(frame, 0, length)
        lastUs = ptsUs
        bytes += size
    }
    override fun sync() { buffered?.flush(); stream?.fd?.sync() }
    override fun close() {
        val closing = stream ?: return
        val buffer = checkNotNull(buffered)
        stream = null
        buffered = null
        try { buffer.flush(); closing.fd.sync() } finally { closing.close() }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        onClosed(name, firstUs, lastUs, bytes, hash)
    }
}
