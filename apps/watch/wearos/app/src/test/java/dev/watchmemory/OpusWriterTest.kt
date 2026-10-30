package dev.watchmemory

import dev.watchmemory.recording.infrastructure.storage.*
import dev.watchmemory.recording.infrastructure.opus.*
import dev.watchmemory.recording.presentation.recording.UiText

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class OpusWriterTest {
    private fun header() = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray()).put(1).put(1).putShort(104).putInt(16000).putShort(0).put(0).array()
    @Test fun androidWrappedHeaderConvertsDelayTo48kUnits() {
        val csd = ByteBuffer.allocate(59).order(ByteOrder.LITTLE_ENDIAN)
            .put("AOPUSHDR".toByteArray()).putLong(19).put(header())
            .put("AOPUSDLY".toByteArray()).putLong(8).putLong(6_500_000).array()
        val result = OpusHeaders.identification(csd)
        assertEquals(312, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
        assertThrows(IllegalArgumentException::class.java) { OpusHeaders.identification(csd.copyOf(40)) }
    }
    @Test fun rotationPrerollCrcHashesAndExplicitSyncPreserveDuration() {
        val dir = Files.createTempDirectory("ogg").toFile()
        try {
            val hashes = mutableMapOf<String, String>()
            val head = header().apply { this[10] = 0x38; this[11] = 1 }
            OpusSegmentWriter(dir, "run", 1_000_000) { name, _, _, _, hash -> hashes[name] = hash }.use { writer ->
                writer.configureHeader(head)
                repeat(151) { i ->
                    writer.write(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), i * 20000L)
                    if (i == 75 || i == 150) writer.sync()
                }
            }
            var total = 0L
            val files = dir.listFiles()!!.sortedBy { it.name }
            assertEquals(4, files.size)
            files.forEachIndexed { index, file ->
                val data = file.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hashes[file.name], hash)
                var offset = 0; var sequence = 0; var granule = 0L; var skip = 0; var flags = 0
                while (offset < data.size) {
                    val count = data[offset+26].toInt() and 255
                    val payload = (0 until count).sumOf { data[offset+27+it].toInt() and 255 }
                    val page = data.copyOfRange(offset, offset+27+count+payload)
                    val buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(sequence++, buffer.getInt(18))
                    val expected = buffer.getInt(22); buffer.putInt(22,0)
                    assertEquals(expected,OggCrc.calculate(page))
                    flags = page[5].toInt();granule = buffer.getLong(6)
                    if(flags and 2 != 0) skip=buffer.getShort(27+count+10).toInt() and 65535
                    offset += page.size
                }
                assertEquals(if(index==0)312 else 3840,skip)
                assertEquals(4,flags and 4)
                total += granule-skip
            }
            assertEquals(151*960L-312,total)
        } finally { dir.deleteRecursively() }
    }
}
