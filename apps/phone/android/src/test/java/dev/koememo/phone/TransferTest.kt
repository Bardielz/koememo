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
    @Test fun rejectsPathsOutsideLibrary() {
        assertThrows(IllegalArgumentException::class.java) { Segment("session","../x.ogg",7,"a".repeat(64)) }
    }
}
