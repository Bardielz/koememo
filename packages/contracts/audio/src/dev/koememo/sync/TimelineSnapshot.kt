package dev.koememo.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File

private val timelineEvent = Regex(""""event"\s*:\s*"(run_started|audio_configuration|quiet_gate|marker|segment_closed|run_stopped|error)"""")

/** Index closed segments and quiet gaps before placing batched samples on the saved audio. */
fun writeTimelineSnapshot(output: DataOutputStream, journal: File, catalog: Map<String, String>,
    includeContext: Boolean, progress: () -> Unit) {
    journal.inputStream().use { stream ->
        val rows = sequence {
            val index = TimelineIndex()
            val windows = SensorWindows(index)
            var linesRead = 0
            var sawSensors = false
            var pulseNs = System.nanoTime()
            suspend fun SequenceScope<String>.keepAlive() {
                if (System.nanoTime() - pulseNs < 10_000_000_000L) return
                yield("""{"event":"timeline_progress"}""")
                output.flush()
                pulseNs = System.nanoTime()
            }
            val iterator = stream.bufferedReader().lineSequence().iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                linesRead++
                if (includeContext && linesRead % 128 == 0) keepAlive()
                if (!timelineEvent.containsMatchIn(line)) {
                    if (includeContext && !sawSensors && line.contains("\"sensor_batch\"")) sawSensors = true
                    continue
                }
                val event = try { JSONObject(line) } catch (e: JSONException) {
                    if (iterator.hasNext()) throw e
                    break // A live recording may still be appending its last line.
                }
                if (event.optString("event") == "segment_closed" &&
                    catalog[event.optString("file")] != event.optString("sha256")) continue
                val projected = TimelineJournal.project(event) ?: continue
                if (includeContext) {
                    index.accept(projected)
                    if (event.optString("event") == "run_started" && event.optBoolean("sensor_timeline")) {
                        windows.enabled(event.optString("run"))
                    }
                }
                yield(projected.toString())
            }
            if (includeContext && sawSensors) {
                stream.channel.position(0)
                var scanned = 0
                for (line in stream.bufferedReader().lineSequence().take(linesRead)) {
                    if (++scanned % 128 == 0) keepAlive()
                    if (!line.contains("\"sensor_batch\"")) continue
                    val batch = try { JSONObject(line) } catch (_: JSONException) { continue }
                    if (batch.optString("event") == "sensor_batch") {
                        val samples = batch.optJSONArray("samples") ?: continue
                        windows.add(batch.optString("run"), samples)
                    }
                }
            }
            if (includeContext) yieldAll(windows.rows())
        }
        TimelineTransfer.write(output, rows, progress)
    }
}
