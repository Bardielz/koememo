package dev.watchmemory

import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Ogg Opus with one-second pages and 80ms skipped decoder preroll at segment boundaries. */
class OpusSegmentWriter(
    private val directory: File,
    private val prefix: String,
    private val segmentUs: Long = 300_000_000,
    private val onClosed: (String, Long, Long, Long, String) -> Unit
) : RecordingWriter {
    private data class Packet(val data: ByteArray, val samples: Int)
    private val history = ArrayDeque<Packet>()
    private var historySamples = 0
    private var header: ByteArray? = null
    private var stream: FileOutputStream? = null
    private var buffered: BufferedOutputStream? = null
    private var index = 0
    private var firstUs = 0L
    private var lastUs = Long.MIN_VALUE
    private var file: File? = null
    private var granule = 0L
    private var sequence = 0
    private var serial = 0
    private var bytesWritten = 0L
    private var digest = MessageDigest.getInstance("SHA-256")
    private val pagePackets = ArrayList<ByteArray>()
    private var pageSamples = 0
    private var pageLacing = 0

    override fun configure(format: MediaFormat) {
        val csd = checkNotNull(format.getByteBuffer("csd-0")).duplicate()
        configureHeader(ByteArray(csd.remaining()).also { csd.get(it) })
    }
    fun configureHeader(csd: ByteArray) {
        check(header == null) { "Opus format changed during recording" }
        header = OpusHeaders.identification(csd)
    }
    override fun write(frame: ByteArray, ptsUs: Long, length: Int) {
        require(length in 1..frame.size && length <= 8191)
        require(ptsUs >= lastUs) { "Non-monotonic Opus timestamp" }
        val packet = Packet(frame.copyOf(length), OpusPackets.samples(frame, length))
        if (stream != null && ptsUs - firstUs >= segmentUs) closeSegment()
        if (stream == null) open(ptsUs)
        append(packet)
        lastUs = ptsUs
        history.addLast(packet); historySamples += packet.samples
        while (history.size > 1 && historySamples - history.first().samples >= 3840) {
            historySamples -= history.removeFirst().samples
        }
    }
    private fun open(ptsUs: Long) {
        val identification = checkNotNull(header) { "Opus output format missing" }.copyOf()
        if (index > 0) {
            identification[10] = historySamples.toByte()
            identification[11] = (historySamples shr 8).toByte()
        }
        val target = File(directory, "$prefix-${String.format(Locale.US, "%04d", ++index)}.ogg")
        check(target.createNewFile()) { "Refusing to overwrite ${target.name}" }
        file = target; stream = FileOutputStream(target); buffered = BufferedOutputStream(stream!!, 32768)
        firstUs = ptsUs; granule = 0; sequence = 0
        serial = UUID.randomUUID().hashCode(); bytesWritten = 0
        digest = MessageDigest.getInstance("SHA-256")
        page(listOf(identification), 2, 0)
        val vendor = "WatchMemory".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII)).putInt(vendor.size).put(vendor).putInt(0).array()
        page(listOf(tags), 0, 0)
        history.forEach { append(it) }
    }
    private fun append(packet: Packet) {
        val lacing = packet.data.size / 255 + 1
        if (pageLacing + lacing > 255 || pageSamples >= 48000) flushPage(false)
        pagePackets.add(packet.data); pageLacing += lacing; pageSamples += packet.samples
        granule += packet.samples
    }
    private fun flushPage(eos: Boolean) {
        if (pagePackets.isEmpty() && !eos) return
        page(pagePackets, if (eos) 4 else 0, granule)
        pagePackets.clear(); pageLacing = 0; pageSamples = 0
    }
    private fun page(packets: List<ByteArray>, flags: Int, position: Long) {
        val segments = packets.sumOf { it.size / 255 + 1 }
        val page = ByteBuffer.allocate(27 + segments + packets.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(flags.toByte())
            .putLong(position).putInt(serial).putInt(sequence++).putInt(0).put(segments.toByte())
        packets.forEach { packet -> repeat(packet.size / 255) { page.put(255.toByte()) }; page.put((packet.size % 255).toByte()) }
        packets.forEach { page.put(it) }
        val data = page.array()
        page.putInt(22, OggCrc.calculate(data))
        buffered!!.write(data); digest.update(data); bytesWritten += data.size
    }
    override fun sync() { if (stream != null) { flushPage(false); buffered!!.flush(); stream!!.fd.sync() } }
    private fun closeSegment() {
        val closing = stream ?: return
        try { flushPage(true); buffered!!.flush(); closing.fd.sync() }
        finally { stream = null; buffered = null; closing.close() }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        onClosed(checkNotNull(file).name, firstUs, lastUs, bytesWritten, hash)
    }
    override fun close() { closeSegment() }
}

object OggCrc {
    private val table = IntArray(256) { value ->
        var crc = value shl 24
        repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        crc
    }
    fun calculate(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { crc = (crc shl 8) xor table[((crc ushr 24) xor (it.toInt() and 255)) and 255] }
        return crc
    }
}
