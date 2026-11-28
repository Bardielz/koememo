package dev.koememo.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionPlaybackTest {
    private fun queue(vararg lengths: Long?) =
        SessionPlayback(lengths.mapIndexed { i, ms -> AudioTrack(File("audio$i"), ms, null) })

    @Test fun seekAcrossFilesAndReplayFromStart() {
        val queue = queue(3000L, 4500L)
        assertEquals(7500L, queue.totalMs)
        assertEquals(AudioPosition(0, 2999), queue.locate(2999))
        assertEquals(AudioPosition(1, 0), queue.locate(3000))
        assertEquals(AudioPosition(1, 4500), queue.locate(10000))
        assertEquals(3500L, queue.elapsed(1, 500))
        assertEquals(AudioPosition(0, 0), queue.locate(-10))
    }
    @Test fun unknownDurationKeepsQueueButDoesNotInventAbsoluteTime() {
        val queue = queue(null, 1200L)
        assertEquals(2, queue.tracks.size)
        assertNull(queue.totalMs)
        assertNull(queue.locate(600))
        assertNull(queue().totalMs)
    }
}
