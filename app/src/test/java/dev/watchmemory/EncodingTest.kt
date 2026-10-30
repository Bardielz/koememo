package dev.watchmemory

import org.junit.Assert.*
import org.junit.Test

class EncodingTest {
    @Test fun masterSwitchOverridesSavedOpusAndBitrate() {
        assertEquals(RecordingEncoding.AAC, RecordingEncoding.effective(false, "OPUS"))
        assertEquals(48000, RecordingEncoding.bitrate(false, RecordingEncoding.OPUS, 24000))
        assertEquals(RecordingEncoding.OPUS, RecordingEncoding.effective(true, "OPUS"))
        assertEquals(RecordingEncoding.AAC, RecordingEncoding.effective(true, "invalid"))
        assertEquals(24000, RecordingEncoding.bitrate(true, RecordingEncoding.OPUS, 48000))
        assertEquals(32000, RecordingEncoding.bitrate(true, RecordingEncoding.OPUS, 32000))
    }
    @Test fun opusPacketDurationsUse48kClock() {
        assertEquals(960, OpusPackets.samples(byteArrayOf(0xf8.toByte())))
        assertEquals(3840, OpusPackets.samples(byteArrayOf(0xfb.toByte(), 4)))
        assertEquals(480, OpusPackets.samples(byteArrayOf(0)))
        assertEquals(2880, OpusPackets.samples(byteArrayOf(0x18)))
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.samples(byteArrayOf(0xfb.toByte(), 63)) }
        assertThrows(IllegalArgumentException::class.java) { OpusPackets.samples(byteArrayOf(3)) }
    }
}
