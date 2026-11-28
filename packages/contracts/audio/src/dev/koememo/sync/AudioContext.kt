package dev.koememo.sync

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// Playback offsets are local to a closed audio file, matching the phone's ASR windows.
const val CONTEXT_MS = 15_000

private fun PlaybackSpan.windowEnd(startMs: Int): Int =
    minOf(startMs.toLong() + CONTEXT_MS, (endUs - startUs + 999) / 1000).toInt()

data class ContextWindow(
    val startMs: Int, val endMs: Int,
    val motionCount: Int, val meanMotion: Double?,
    val heartCount: Int, val validHeartCount: Int, val heartSeconds: Int,
    val minBpm: Double?, val maxBpm: Double?
) {
    val seconds: Int get() = (endMs - startMs + 999) / 1000

    internal fun encode(run: String, span: PlaybackSpan) = JSONObject()
        .put("event", "audio_context").put("run", run).put("file", span.file).put("sha256", span.hash)
        .put("start_ms", startMs).put("motion_count", motionCount).put("mean_enmo", meanMotion)
        .put("heart_count", heartCount).put("valid_heart_count", validHeartCount)
        .put("heart_seconds", heartSeconds).put("min_bpm", minBpm).put("max_bpm", maxBpm)

    companion object {
        internal fun read(e: JSONObject, span: PlaybackSpan): ContextWindow? {
            val start = e.optInt("start_ms", -1)
            val end = span.windowEnd(start)
            if (start < 0 || start % CONTEXT_MS != 0 || end <= start) return null
            val motion = e.optInt("motion_count", -1)
            val heart = e.optInt("heart_count", -1)
            val valid = e.optInt("valid_heart_count", -1)
            val seconds = e.optInt("heart_seconds", -1)
            val enmo = e.optDouble("mean_enmo").takeIf { it.isFinite() && it >= 0 }
            val min = e.optDouble("min_bpm").takeIf { it.isFinite() && it > 0 }
            val max = e.optDouble("max_bpm").takeIf { it.isFinite() && it > 0 }
            if (motion < 0 || valid !in 0..heart || seconds !in 0..minOf(valid, (end-start+999)/1000)) return null
            if ((motion > 0) != (enmo != null) || (valid > 0) != (seconds > 0)) return null
            if ((valid > 0) != (min != null) || (valid > 0) != (max != null) || (min != null && max != null && min > max)) return null
            return ContextWindow(start, end, motion, enmo, heart, valid, seconds, min, max)
        }
    }
}

/** Holds only aggregate statistics while scanning timestamped samples on the watch during sync. */
internal class SensorWindows(private val index: TimelineIndex) {
    private class Stats {
        var motionCount = 0
        var motionSum = 0.0
        var heartCount = 0
        var validHeartCount = 0
        var secondsMask = 0
        var minBpm = Double.POSITIVE_INFINITY
        var maxBpm = Double.NEGATIVE_INFINITY
    }

    private val buckets = mutableMapOf<String, MutableMap<String, MutableMap<Int, Stats>>>()
    private val enabledRuns = mutableSetOf<String>()

    fun enabled(runId: String) { enabledRuns.add(runId) }

    fun add(runId: String, samples: JSONArray) {
        val run = index.runs[runId] ?: return
        enabled(runId)
        for (i in 0 until samples.length()) {
            val row = samples.optJSONArray(i) ?: continue
            val type = row.optInt(1)
            if (type != 1 && type != 21) continue
            val ns = row.optLong(0, -1)
            if (ns < 0) continue
            val position = run.locate(ns) as? MarkPosition.Audio ?: continue
            val start = position.offsetMs / CONTEXT_MS * CONTEXT_MS
            val byFile = buckets.getOrPut(runId) { mutableMapOf() }
            val byTime = byFile.getOrPut(position.file) { sortedMapOf() }
            val stats = byTime.getOrPut(start) { Stats() }
            if (type == 1) {
                val x = row.optDouble(3)
                val y = row.optDouble(4)
                val z = row.optDouble(5)
                val magnitude = sqrt(x*x + y*y + z*z)
                if (!magnitude.isFinite()) continue
                stats.motionSum += (magnitude - 9.80665).coerceAtLeast(0.0)
                stats.motionCount++
            } else {
                stats.heartCount++
                val bpm = row.optDouble(3)
                if (row.optInt(2) !in 1..3 || !bpm.isFinite() || bpm <= 0) continue
                stats.validHeartCount++
                stats.minBpm = minOf(stats.minBpm, bpm)
                stats.maxBpm = maxOf(stats.maxBpm, bpm)
                stats.secondsMask = stats.secondsMask or (1 shl ((position.offsetMs - start) / 1000))
            }
        }
    }

    fun rows(): Sequence<String> = sequence {
        for ((id, run) in index.runs) {
            if (id !in enabledRuns || !run.hasAudioClock) continue
            val empty = Stats()
            for (span in run.spans) {
                val windows = buckets[id]?.get(span.file).orEmpty()
                val durationMs = (span.endUs - span.startUs + 999) / 1000
                for (start in 0 until durationMs.toInt() step CONTEXT_MS) {
                    val stats = windows[start] ?: empty
                    val window = ContextWindow(start, span.windowEnd(start), stats.motionCount,
                        if (stats.motionCount == 0) null else stats.motionSum / stats.motionCount,
                        stats.heartCount, stats.validHeartCount, Integer.bitCount(stats.secondsMask),
                        stats.minBpm.takeIf { it.isFinite() }, stats.maxBpm.takeIf { it.isFinite() })
                    yield(window.encode(id, span).toString())
                }
            }
        }
    }
}
