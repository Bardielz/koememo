package dev.watchmemory.recording.domain

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
}
