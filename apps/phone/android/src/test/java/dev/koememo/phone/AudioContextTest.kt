package dev.koememo.phone

import dev.koememo.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class AudioContextTest {
    private val hash = "a".repeat(64)
    private val journal = """
        {"event":"run_started","run":"r","sample_rate":16000,"elapsed_ms":1000,"wall_ms":1700000000000}
        {"event":"sensor_batch","run":"r","samples":[[16000000000,1,3,0,0,11.80665],[11000000000,21,3,190],[16003000000,21,2,80],[16001000000,21,3,70]]}
        {"event":"audio_configuration","audio_boottime_ns":1000000000,"audio_frame_position":0}
        {"event":"quiet_gate","action":"pause","captured_samples":82048,"held_samples":2048,"replay_samples":0}
        {"event":"quiet_gate","action":"resume","captured_samples":242048,"held_samples":160000,"replay_samples":16000}
        {"event":"segment_closed","run":"r","file":"one.aac","sha256":"$hash","playback_start_us":0,"end_pts_us":4000000}
        {"event":"segment_closed","run":"r","file":"two.aac","sha256":"$hash","playback_start_us":4000000,"end_pts_us":20950000}
        {"event":"audio_configuration","capture_end_samples":479200}
        {"event":"sensor_batch","run":"r","samples":[[15000000000,1,3,0,0,9.80665],[18000000000,21,-1,180],[18000000001,21,0,120],[19000000000,21,3,null],[29000000000,21,-1,0],[30950000000,21,3,199],[5000000000,1,3,0,0,9.80665],[4999000000,21,2,88]]}
        {"event":"sensor_batch","run":"other","samples":[[16000000000,21,3,200]]}
        {"event":"run_stopped"}
    """.trimIndent()

    private fun snapshot(includeContext: Boolean = true, content: String = journal,
        catalog: Map<String, String> = mapOf("one.aac" to hash, "two.aac" to hash)): List<String> {
        val dir = Files.createTempDirectory("context-wire").toFile()
        try {
            val source = File(dir, "events.jsonl").apply { writeText(content) }
            val wire = ByteArrayOutputStream()
            writeTimelineSnapshot(DataOutputStream(wire), source, catalog, includeContext) {}
            TimelineTransfer.receive(DataInputStream(wire.toByteArray().inputStream()), dir) {}
            return File(dir, TimelineJournal.FILE).readLines()
        } finally { dir.deleteRecursively() }
    }

    @Test fun rawToPhoneUsesAcquisitionTimeClosedFilesAndQuality() {
        val rows = snapshot()
        assertFalse(rows.any { it.contains("sensor_batch") || it.contains("samples\":[[") })
        val spans = readTimeline(rows.asSequence()).single().spans
        assertEquals(88.0, spans.first().contextAt(3999)!!.minBpm!!, 0.0)
        val second = spans.last()
        val first = second.contextAt(0)!!
        assertEquals(first, second.contextAt(14999))
        assertEquals(3, first.motionCount)
        assertEquals(2.0/3, first.meanMotion!!, 0.00001)
        assertEquals(5, first.heartCount)
        assertEquals(2, first.validHeartCount)
        assertEquals(70.0, first.minBpm!!, 0.0)
        assertEquals(80.0, first.maxBpm!!, 0.0)
        assertEquals(1, first.heartSeconds)
        assertEquals(14, first.seconds - first.heartSeconds)
        val tail = second.contextAt(15000)!!
        assertEquals(16950, tail.endMs)
        assertEquals(2, tail.seconds)
        assertEquals(1, tail.heartCount)
        assertEquals(0, tail.validHeartCount)
        assertNull(tail.minBpm)
        assertNull(tail.meanMotion)
        assertNull(second.contextAt(16950))
    }

    @Test fun legacySnapshotsAndFilesMissingFromCatalogRevealNoContext() {
        assertFalse(snapshot(false).any { it.contains("audio_context") || it.contains("sensor_batch") })
        assertFalse(snapshot(catalog = emptyMap()).any { it.contains("audio_context") })
        val noAnchors = journal.lines().filterNot { it.contains("audio_boottime_ns") }.joinToString("\n")
        assertFalse(snapshot(content = noAnchors).any { it.contains("audio_context") })
        val noSensors = journal.lines().filterNot { it.contains("sensor_batch") }.joinToString("\n")
        assertFalse(snapshot(content = noSensors).any { it.contains("audio_context") })
        val enabled = noSensors.replace("\"sample_rate\":16000", "\"sample_rate\":16000,\"sensor_timeline\":true")
        val empty = readTimeline(snapshot(content = enabled).asSequence()).single().spans.last().contextAt(15000)!!
        assertEquals(2, empty.seconds)
        assertEquals(0, empty.heartCount)
        assertEquals(0, empty.heartSeconds)
        assertNull(empty.meanMotion)
    }

    @Test fun malformedOrMismatchedSummariesDoNotAttachToAudio() {
        val rows = snapshot()
        val modified = rows.map { line ->
            val e = JSONObject(line)
            if (e.getString("event") != "audio_context") line
            else if (e.getString("file") == "one.aac") e.put("sha256", "other").toString()
            else e.put("heart_seconds", 100).toString()
        }
        assertTrue(readTimeline(modified.asSequence()).single().spans.all { it.windows.isEmpty() })
    }
}
