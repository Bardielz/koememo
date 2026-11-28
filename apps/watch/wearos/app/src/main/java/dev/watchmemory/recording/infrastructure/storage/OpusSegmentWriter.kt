package dev.watchmemory.recording.infrastructure.storage

import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.infrastructure.opus.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Ogg Opus with one-second pages and 80ms skipped decoder preroll at segment boundaries. */
class OpusSegmentWriter @JvmOverloads constructor(
    private val directory: File,
    private val prefix: String,
    private val segmentUs: Long = 300_000_000,
    private val reuseBuffers: Boolean = false,
    private val onClosed: (String, Long, Long, Long, String, Long, Long) -> Unit
) : RecordingWriter {
    private class Packet(var data: ByteArray, var length: Int, var samples: Int) {
        var inHistory = false
        var inPage = false
    }
    private val spare = ArrayDeque<Packet>()
    private var reusablePage = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private val history = ArrayDeque<Packet>()
    private var historySamples = 0
    private var header: ByteArray? = null
    private var stream: FileOutputStream? = null
    private var buffered: BufferedOutputStream? = null
    private var index = 0
    private var playbackStartUs = 0L
    private var lastPacketSamples = 0
    private var firstUs = 0L
    private var lastUs = Long.MIN_VALUE
    private var file: File? = null
    private var granule = 0L
    private var sequence = 0
    private var serial = 0
    private var bytesWritten = 0L
    private var digest = MessageDigest.getInstance("SHA-256")
    private val pagePackets = ArrayList<Packet>()
    private var pageSamples = 0
    private var pageLacing = 0

    override fun configure(format: EncodedAudioFormat) {
        configureHeader(checkNotNull(format.codecSpecificData))
    }
    fun configureHeader(csd: ByteArray) {
        check(header == null) { "Opus format changed during recording" }
        header = OpusHeaders.identification(csd)
    }
    override fun write(frame: ByteArray, ptsUs: Long, length: Int) {
        require(length in 1..frame.size && length <= 8191)
        require(ptsUs >= lastUs) { "Non-monotonic Opus timestamp" }
        val samples = OpusPackets.samples(frame, length)
        val packet = if (reuseBuffers && spare.isNotEmpty()) {
            spare.removeLast().also {
                if (it.data.size < length) it.data = ByteArray(length)
                frame.copyInto(it.data, endIndex = length)
                it.length = length
                it.samples = samples
            }
        } else Packet(frame.copyOf(length), length, samples)
        if (stream != null && ptsUs - firstUs >= segmentUs) closeSegment()
        if (stream == null) open(ptsUs)
        append(packet)
        lastPacketSamples = samples
        lastUs = ptsUs
        packet.inHistory = true
        history.addLast(packet); historySamples += packet.samples
        while (history.size > 1 && historySamples - history.first().samples >= 3840) {
            val old = history.removeFirst()
            historySamples -= old.samples
            old.inHistory = false
            if (reuseBuffers && !old.inPage) spare.addLast(old)
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
        val preSkip = (identification[10].toInt() and 255) or ((identification[11].toInt() and 255) shl 8)
        playbackStartUs = ptsUs + (preSkip - historySamples) * 1_000_000L / 48000
        firstUs = ptsUs; granule = 0; sequence = 0
        serial = UUID.randomUUID().hashCode(); bytesWritten = 0
        digest = MessageDigest.getInstance("SHA-256")
        page(listOf(Packet(identification, identification.size, 0)), 2, 0)
        val vendor = "WatchMemory".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII)).putInt(vendor.size).put(vendor).putInt(0).array()
        page(listOf(Packet(tags, tags.size, 0)), 0, 0)
        history.forEach { append(it) }
    }
    private fun append(packet: Packet) {
        val lacing = packet.length / 255 + 1
        if (pageLacing + lacing > 255 || pageSamples >= 48000) flushPage(false)
        packet.inPage = true
        pagePackets.add(packet); pageLacing += lacing; pageSamples += packet.samples
        granule += packet.samples
    }
    private fun flushPage(eos: Boolean) {
        if (pagePackets.isEmpty() && !eos) return
        page(pagePackets, if (eos) 4 else 0, granule)
        for (packet in pagePackets) {
            packet.inPage = false
            if (reuseBuffers && !packet.inHistory) spare.addLast(packet)
        }
        pagePackets.clear(); pageLacing = 0; pageSamples = 0
    }
    private fun page(packets: List<Packet>, flags: Int, position: Long) {
        val segments = packets.sumOf { it.length / 255 + 1 }
        val length = 27 + segments + packets.sumOf { it.length }
        val page = if (reuseBuffers) {
            if (reusablePage.capacity() < length) {
                reusablePage = ByteBuffer.allocate(maxOf(length, reusablePage.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN)
            }
            reusablePage.clear()
            reusablePage
        } else ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(flags.toByte())
            .putLong(position).putInt(serial).putInt(sequence++).putInt(0).put(segments.toByte())
        packets.forEach { packet -> repeat(packet.length / 255) { page.put(255.toByte()) }; page.put((packet.length % 255).toByte()) }
        packets.forEach { page.put(it.data, 0, it.length) }
        val data = page.array()
        page.putInt(22, OggCrc.calculate(data, length))
        buffered!!.write(data, 0, length); digest.update(data, 0, length); bytesWritten += length
    }
    override fun sync() { if (stream != null) { flushPage(false); buffered!!.flush(); stream!!.fd.sync() } }
    private fun closeSegment() {
        val closing = stream ?: return
        try { flushPage(true); buffered!!.flush(); closing.fd.sync() }
        finally { stream = null; buffered = null; closing.close() }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        onClosed(checkNotNull(file).name, firstUs, lastUs, bytesWritten, hash, playbackStartUs, lastUs + lastPacketSamples * 1_000_000L / 48000)
    }
    override fun close() { closeSegment() }
}

object OggCrc {
    private val table = IntArray(256) { value ->
        var crc = value shl 24
        repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        crc
    }
    fun calculate(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0
        for (i in 0 until length) crc = (crc shl 8) xor table[((crc ushr 24) xor bytes[i].toInt()) and 255]
        return crc
    }
}
