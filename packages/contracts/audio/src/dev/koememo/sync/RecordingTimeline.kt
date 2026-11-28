package dev.koememo.sync

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/** Only these journal fields are shared with a paired phone. Sensor and diagnostic payloads stay local. */
object TimelineJournal {
    const val FILE = "timeline-v1.jsonl"
    private val fields = mapOf(
        "run_started" to listOf("run", "sample_rate", "elapsed_ms", "wall_ms"),
        "audio_configuration" to listOf("audio_boottime_ns", "audio_frame_position", "capture_end_samples"),
        "quiet_gate" to listOf("action", "captured_samples", "held_samples", "replay_samples"),
        "marker" to listOf("marker_boottime_ns", "wall_ms", "elapsed_ms"),
        "segment_closed" to listOf("run", "file", "sha256", "playback_start_us", "end_pts_us"),
        "run_stopped" to emptyList(), "error" to emptyList()
    )

    fun project(event: JSONObject): JSONObject? {
        val type = event.optString("event")
        val keys = fields[type] ?: return null
        val result = JSONObject().put("event", type)
        for (key in keys) if (event.has(key)) result.put(key, event.get(key))
        if (type == "audio_configuration" && result.length() == 1) return null
        return result
    }
}

data class TimelineMark(val wallMs: Long, val bootNs: Long)
data class PlaybackSpan(val file: String, val startUs: Long, val endUs: Long, val hash: String)
sealed class MarkPosition {
    data class Audio(val file: String, val offsetMs: Int, val hash: String) : MarkPosition()
    object Omitted : MarkPosition()
    object Unknown : MarkPosition()
}

class RunTimeline(val rate: Int, private val elapsedMs: Long, private val wallMs: Long) {
    private data class Anchor(val ns: Long, val frame: Long)
    private data class Gap(val start: Long, val end: Long)
    private val anchors = mutableListOf<Anchor>()
    private val gaps = mutableListOf<Gap>()
    private var pendingGap: Long? = null
    private var captureEnd: Long? = null
    val spans = mutableListOf<PlaybackSpan>()
    val marks = mutableListOf<TimelineMark>()

    init { require(rate in 1..192000) }

    fun anchor(ns: Long, frame: Long) { if (ns > 0 && frame >= 0) anchors.add(Anchor(ns, frame)) }
    fun finish(frame: Long) { require(frame >= 0); captureEnd = frame }
    fun gate(action: String, captured: Long, held: Long, replay: Int) {
        require(held in 0..captured && replay.toLong() in 0..held)
        when (action) {
            "pause" -> { require(pendingGap == null); pendingGap = captured - held }
            "resume", "stop" -> {
                val start = requireNotNull(pendingGap)
                val end = start + held - replay
                require(end in start..captured && start >= (gaps.lastOrNull()?.end ?: 0L))
                gaps.add(Gap(start, end))
                pendingGap = null
            }
            else -> error("Unknown quiet transition")
        }
    }

    fun locate(mark: TimelineMark): MarkPosition {
        val a = anchors.minByOrNull { abs(it.ns - mark.bootNs) } ?: return MarkPosition.Unknown
        val frame = a.frame + ((mark.bootNs - a.ns) * (rate / 1e9)).roundToLong()
        if (frame < 0 || frame >= (captureEnd ?: Long.MAX_VALUE) || frame >= (pendingGap ?: Long.MAX_VALUE))
            return MarkPosition.Unknown
        var omitted = 0L
        for (gap in gaps) {
            if (frame < gap.start) break
            if (frame < gap.end) return MarkPosition.Omitted
            omitted += gap.end - gap.start
        }
        val mediaUs = (frame - omitted) * 1_000_000L / rate
        val span = spans.lastOrNull { mediaUs >= it.startUs && mediaUs < it.endUs } ?: return MarkPosition.Unknown
        return MarkPosition.Audio(span.file, ((mediaUs - span.startUs) / 1000).toInt(), span.hash)
    }

    /** Approximate local wall clock for a transcript window, derived from this run's clock pair. */
    fun timeAt(file: String, offsetMs: Long): Long? {
        val span = spans.firstOrNull { it.file == file } ?: return null
        val mediaUs = span.startUs + offsetMs * 1000
        if (offsetMs < 0 || mediaUs >= span.endUs) return null
        var frame = (mediaUs * rate / 1e6).roundToLong().coerceAtLeast(0)
        for (gap in gaps) {
            if (frame < gap.start) break
            frame += gap.end - gap.start
        }
        if (frame >= (pendingGap ?: Long.MAX_VALUE) || frame >= (captureEnd ?: Long.MAX_VALUE)) return null
        val a = anchors.minByOrNull { abs(it.frame - frame) } ?: return null
        return wallMs + (a.ns / 1e6 + (frame - a.frame) * 1000.0 / rate - elapsedMs).roundToLong()
    }
}

fun readTimeline(lines: Sequence<String>): List<RunTimeline> {
    val runs = linkedMapOf<String, RunTimeline>()
    var active: RunTimeline? = null
    for (line in lines) {
        val e = JSONObject(line)
        when (e.getString("event")) {
            "run_started" -> {
                active = null
                val rate = e.optInt("sample_rate")
                if (rate !in 1..192000 || !e.has("elapsed_ms") || !e.has("wall_ms") || !e.has("run")) continue
                val run = RunTimeline(rate, e.getLong("elapsed_ms"), e.getLong("wall_ms"))
                runs[e.getString("run")] = run
                active = run
            }
            "run_stopped", "error" -> active = null
            "audio_configuration" -> {
                if (e.has("audio_boottime_ns")) active?.anchor(e.getLong("audio_boottime_ns"), e.getLong("audio_frame_position"))
                if (e.has("capture_end_samples")) active?.finish(e.getLong("capture_end_samples"))
            }
            "quiet_gate" -> active?.gate(e.getString("action"), e.getLong("captured_samples"),
                e.getLong("held_samples"), e.getInt("replay_samples"))
            "marker" -> active?.marks?.add(TimelineMark(e.getLong("wall_ms"),
                e.optLong("marker_boottime_ns", e.getLong("elapsed_ms") * 1_000_000)))
            "segment_closed" -> if (e.has("playback_start_us") && e.has("end_pts_us")) {
                val name = e.getString("file")
                require(name.matches(Regex("[A-Za-z0-9-]{1,120}\\.(aac|ogg)")))
                runs[e.getString("run")]?.spans?.add(PlaybackSpan(name, e.getLong("playback_start_us"), e.getLong("end_pts_us"), e.getString("sha256")))
            }
        }
    }
    return runs.values.toList()
}

fun sessionTimeline(audio: File): List<RunTimeline> {
    val journal = File(audio.parentFile, TimelineJournal.FILE)
    return if (journal.isFile) journal.useLines { readTimeline(it) } else emptyList()
}
