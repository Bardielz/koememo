package dev.koememo.phone

import dev.koememo.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class RecordingTimelineTest {
    private val wall = 1_700_000_000_000L
    private val hash = "b".repeat(64)
    private fun mark(seconds: Int) = TimelineMark(wall + seconds * 1000L, (1L + seconds) * 1_000_000_000)

    @Test fun gapAndReplayUseAcquisitionTimeAcrossSegments() {
        val run = RunTimeline(16000, 1000, wall)
        run.anchor(1_000_000_000, 0)
        run.spans += PlaybackSpan("one.aac", 0, 4_000_000, hash)
        run.spans += PlaybackSpan("two.aac", 4_000_000, 21_000_000, hash)
        run.gate("pause", 82048, 2048, 0)
        assertEquals(MarkPosition.Unknown, run.locate(mark(10)))
        run.gate("resume", 242048, 160000, 16000)
        run.finish(480000)
        assertEquals(MarkPosition.Audio("two.aac", 0, hash), run.locate(mark(4)))
        assertEquals(MarkPosition.Omitted, run.locate(mark(5)))
        assertEquals(MarkPosition.Omitted, run.locate(mark(13)))
        assertEquals(MarkPosition.Audio("two.aac", 1000, hash), run.locate(mark(14)))
        assertEquals(MarkPosition.Audio("two.aac", 2000, hash), run.locate(mark(15)))
        assertEquals(wall + 15000, run.timeAt("two.aac", 2000))
        assertEquals(MarkPosition.Unknown, run.locate(mark(30)))
    }

    @Test fun legacyAndSensorDataStaySeparateFromNewRuns() {
        fun event(type: String, vararg fields: Pair<String, Any>) =
            JSONObject(mapOf("event" to type, *fields))
        val rows = listOf(
            event("run_started", "run" to "legacy", "sample_rate" to 16000, "elapsed_ms" to 10, "wall_ms" to wall),
            event("marker", "elapsed_ms" to 12, "wall_ms" to wall),
            event("run_stopped"),
            event("run_started", "run" to "new", "sample_rate" to 16000, "elapsed_ms" to 1000, "wall_ms" to wall),
            event("sensor_batch", "samples" to "local-only"),
            event("audio_configuration", "audio_boottime_ns" to 1000000000, "audio_frame_position" to 0),
            event("marker", "elapsed_ms" to 2000, "marker_boottime_ns" to 2000000000, "wall_ms" to wall + 1000),
            event("segment_closed", "run" to "new", "file" to "new.ogg", "sha256" to hash,
                "playback_start_us" to 0, "end_pts_us" to 2000000),
            event("run_stopped")
        ).mapNotNull { TimelineJournal.project(it)?.toString() }
        assertFalse(rows.any { it.contains("local-only") })
        val (legacy, current) = readTimeline(rows.asSequence())
        assertEquals(MarkPosition.Unknown, legacy.locate(legacy.marks.single()))
        assertEquals(MarkPosition.Audio("new.ogg", 1000, hash), current.locate(current.marks.single()))
    }

    @Test fun retriesReplaceSnapshotOnlyAfterDigestAndLeaveControlBytesUnread() {
        val dir = Files.createTempDirectory("timeline").toFile()
        try {
            val file = File(dir, TimelineJournal.FILE).apply { writeText("previous") }
            val sink = ByteArrayOutputStream()
            val output = DataOutputStream(sink)
            TimelineTransfer.write(output, sequenceOf("first", "second")) {}
            val complete = sink.toByteArray()
            for (broken in listOf(complete.copyOf(complete.size - 1), complete.clone().apply { this[lastIndex]++ })) {
                assertThrows(IOException::class.java) {
                    TimelineTransfer.receive(DataInputStream(broken.inputStream()), dir) {}
                }
                assertEquals("previous", file.readText())
                assertEquals(listOf(file), dir.listFiles()!!.toList())
            }
            output.writeBoolean(true)
            repeat(2) {
                val input = DataInputStream(sink.toByteArray().inputStream())
                TimelineTransfer.receive(input, dir) {}
                assertTrue(input.readBoolean())
                assertEquals(listOf("first", "second"), file.readLines())
            }
        } finally { dir.deleteRecursively() }
    }
}
