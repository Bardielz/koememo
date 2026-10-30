package dev.watchmemory

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class SegmentWriterTest {
    @Test fun ratesBufferedSyncAndPartialArraysKeepCorrectHeadersAndHashes() {
        for ((rate, index) in listOf(16000 to 8, 24000 to 6, 48000 to 3)) {
            val dir = Files.createTempDirectory("rate-$rate").toFile()
            try {
                var finalizedHash = ""
                val writer = SegmentWriter(dir, "run", sampleRate = rate) { _, _, _, _, hash -> finalizedHash = hash }
                writer.use {
                    val reusable = ByteArray(8191) { 99 }
                    it.write(reusable, 0, 100)
                    it.sync()
                    val synced = dir.listFiles()!!.single().readBytes()
                    assertEquals(107, synced.size)
                    assertEquals(index, (synced[2].toInt() shr 2) and 15)
                    assertArrayEquals(ByteArray(100) { 99 }, synced.copyOfRange(7, synced.size))
                    reusable.fill(42)
                    it.write(reusable, 1024L * 1_000_000 / rate, 120)
                }
                val all = dir.listFiles()!!.single().readBytes()
                assertEquals(234, all.size)
                assertEquals(index, (all[109].toInt() shr 2) and 15)
                assertArrayEquals(ByteArray(120) { 42 }, all.copyOfRange(114, all.size))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it.toInt() and 255) }, finalizedHash)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun rotationPreservesEveryFrameAndVerifiableHashes() {
        val directory = Files.createTempDirectory("segments").toFile()
        try {
            val closed = mutableMapOf<String, String>()
            val original = (0 until 20).map { i -> ByteArray(100 + i) { i.toByte() } }
            SegmentWriter(directory, "run", 192_000) { name, _, _, _, hash -> closed[name] = hash }.use { writer ->
                original.forEachIndexed { i, bytes -> writer.write(bytes, i * 64000L) }
            }
            val decoded = mutableListOf<ByteArray>()
            val files = directory.listFiles()!!.sortedBy { it.name }
            assertEquals(7, files.size)
            files.forEach { file ->
                val bytes = file.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hash, closed[file.name])
                var offset = 0
                while (offset < bytes.size) {
                    assertEquals(255, bytes[offset].toInt() and 255)
                    assertEquals(0xf1, bytes[offset + 1].toInt() and 255)
                    assertEquals(8, (bytes[offset + 2].toInt() shr 2) and 15) // 16kHz
                    assertEquals(1, ((bytes[offset + 2].toInt() and 1) shl 2) or ((bytes[offset + 3].toInt() shr 6) and 3))
                    val size = ((bytes[offset + 3].toInt() and 3) shl 11) or
                        ((bytes[offset + 4].toInt() and 255) shl 3) or ((bytes[offset + 5].toInt() and 255) shr 5)
                    decoded += bytes.copyOfRange(offset + 7, offset + size)
                    offset += size
                }
                assertEquals(bytes.size, offset)
            }
            assertEquals(original.size, decoded.size)
            original.zip(decoded).forEach { (a, b) -> assertArrayEquals(a, b) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun existingRecordingIsNeverOverwritten() {
        val dir = Files.createTempDirectory("collision").toFile()
        try {
            val file = java.io.File(dir, "run-0001.aac").apply { writeText("original") }
            assertThrows(java.io.IOException::class.java) {
                SegmentWriter(dir, "run") { _, _, _, _, _ -> }.use { it.write(byteArrayOf(1), 0) }
            }
            assertEquals("original", file.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsOversizedFrameWithoutCreatingFile() {
        val dir = Files.createTempDirectory("oversize").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SegmentWriter(dir, "run") { _, _, _, _, _ -> }.use { it.write(ByteArray(8192), 0) }
            }
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
