package dev.koememo.phone

import dev.koememo.sync.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

class TransferTest {
    @Test fun partialTransferCanResumeWithoutConsumingControlBytes() {
        val sink = ByteArrayOutputStream()
        assertThrows(EOFException::class.java) { copyExactly(byteArrayOf(1,2).inputStream(), sink, 4) }
        val retry = byteArrayOf(3,4,99).inputStream()
        copyExactly(retry,sink,4L-sink.size())
        assertArrayEquals(byteArrayOf(1,2,3,4),sink.toByteArray())
        assertEquals(99,retry.read())
    }
    @Test fun slowTransferStaysAliveUntilProgressStops() {
        var time = 0L
        val progress = TransferProgress { time }
        progress.enter("send", "recording.aac", 100)
        val sink = object : ByteArrayOutputStream() {
            override fun write(data: ByteArray, offset: Int, length: Int) {
                time += 120_000
                assertFalse(progress.expired(180_000))
                super.write(data, offset, length)
            }
        }
        val data = ByteArray(96 * 1024)
        copyExactly(data.inputStream(), sink, data.size.toLong(), progress::advanced)
        assertEquals(360_000L, time)
        assertEquals(100L + data.size, progress.bytes)
        progress.enter("ack")
        time += 179_999
        assertFalse(progress.expired(180_000))
        time++
        assertTrue(progress.expired(180_000))
    }
    @Test fun rejectsPathsOutsideLibrary() {
        assertThrows(IllegalArgumentException::class.java) { Segment("session","../x.ogg",7,"a".repeat(64)) }
    }
}
