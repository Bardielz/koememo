package dev.watchmemory

import dev.watchmemory.recording.infrastructure.opus.OpusPackets
import org.junit.Assert.*
import org.junit.Test
class OpusPacketsTest {
    @Test fun opusPacketDurationsUse48kClock() {
        assertEquals(960, OpusPackets.samples(byteArrayOf(0xf8.toByte())))
        assertEquals(3840, OpusPackets.samples(byteArrayOf(0xfb.toByte(), 4)))
        assertEquals(480, OpusPackets.samples(byteArrayOf(0)))
        assertEquals(2880, OpusPackets.samples(byteArrayOf(0x18)))
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.samples(byteArrayOf(0xfb.toByte(), 63)) }
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.samples(byteArrayOf(3)) }
    }
}
