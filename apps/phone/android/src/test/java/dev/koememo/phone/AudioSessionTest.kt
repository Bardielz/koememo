package dev.koememo.phone

import dev.koememo.sync.Segment
import dev.koememo.sync.TimelineJournal
import dev.koememo.sync.sha256
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val dir get() = temp.root
    private fun audio(name: String) = File(dir, name).apply { writeText(name) }

    @Test fun manifestCanArriveAheadOfAudioAndOutliveInterruptedTransfer() {
        val a = audio("old.aac")
        File(dir, "new.ogg.part").writeText("partial")
        SyncCatalog.save(dir, listOf(Segment("s", a.name, a.length(), a.sha256()),
            Segment("s", "new.ogg", 30, "a".repeat(64))), 1234)
        val session = AudioSession.load(dir)
        assertEquals(2, session.catalog!!.hashes.size)
        assertEquals(1, session.received)
        assertEquals(listOf(a), session.clips.map { it.file })
        assertNull(session.uncoveredUs)
        assertFalse(session.timelineError)
    }

    @Test fun overlappingPaddedSegmentsDoNotCoverEarlierHolesOrDoubleCountCoverage() {
        val early = audio("z.aac")
        val late = audio("a.ogg")
        File(dir, TimelineJournal.FILE).writeText("""
            {"event":"run_started","run":"r","sample_rate":16000,"wall_ms":1000,"elapsed_ms":1}
            {"event":"quiet_gate","action":"pause","captured_samples":32000,"held_samples":0,"replay_samples":0}
            {"event":"quiet_gate","action":"resume","captured_samples":96000,"held_samples":64000,"replay_samples":16000}
            {"event":"segment_closed","run":"r","file":"z.aac","playback_start_us":0,"end_pts_us":2000000,"sha256":"${early.sha256()}"}
            {"event":"segment_closed","run":"r","file":"a.ogg","playback_start_us":3000000,"end_pts_us":9000000,"sha256":"${late.sha256()}"}
            {"event":"audio_configuration","capture_end_samples":160000}
            {"event":"run_stopped","wall_ms":11000}
        """.trimIndent())
        val session = AudioSession.load(dir)
        assertEquals(listOf(early, late), session.clips.map { it.file })
        assertEquals(3_000_000L, session.omittedUs)
        assertEquals(1_000_000L, session.uncoveredUs)
        assertEquals(11000L, session.runs.single().endWallMs)
        late.writeText("different")
        val mismatch = AudioSession.load(dir)
        assertTrue(mismatch.clips.last().hashMismatch)
        assertNull(mismatch.clips.last().span)
        SyncCatalog.save(dir, listOf(Segment("s", late.name, late.length(), late.sha256())), 5678)
        val replaced = AudioSession.load(dir)
        assertFalse(replaced.clips.last().hashMismatch)
        assertNull(replaced.clips.last().span)
    }

    @Test fun legacyAndInvalidTimelinesLeaveUnknownsAndKeepAudio() {
        val old = audio("old.aac")
        assertNull(AudioSession.load(dir).catalog)
        File(dir, TimelineJournal.FILE).writeText("broken")
        val session = AudioSession.load(dir)
        assertTrue(session.timelineError)
        assertEquals(old, session.clips.single().file)
        assertNull(session.uncoveredUs)
    }
}
