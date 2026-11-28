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
        "run_stopped" to listOf("wall_ms"), "error" to listOf("wall_ms")
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
data class PlaybackSpan(val file: String, val startUs: Long, val endUs: Long, val hash: String) {
    val windows = sortedMapOf<Int, ContextWindow>()
    fun contextAt(offsetMs: Int): ContextWindow? =
        if (offsetMs < 0) null else windows[offsetMs / CONTEXT_MS * CONTEXT_MS]?.takeIf { offsetMs < it.endMs }
}
sealed class MarkPosition {
    data class Audio(val file: String, val offsetMs: Int, val hash: String) : MarkPosition()
    object Omitted : MarkPosition()
    object Unknown : MarkPosition()
}

class RunTimeline(val rate: Int, private val elapsedMs: Long, val wallMs: Long) {
    private data class Anchor(val ns: Long, val frame: Long)
    private data class Gap(val start: Long, val end: Long, val totalOmitted: Long)
    private val anchors = mutableListOf<Anchor>()
    internal val hasAudioClock get() = anchors.isNotEmpty()
    private val gaps = mutableListOf<Gap>()
    private var pendingGap: Long? = null
    private var captureEnd: Long? = null
    val captureUs get() = captureEnd?.let { it * 1_000_000L / rate }
    val omittedUs get() = (gaps.lastOrNull()?.totalOmitted ?: 0L) * 1_000_000L / rate
    val openOmission get() = pendingGap != null
    var endWallMs: Long? = null
        internal set
    var endEvent: String? = null
        internal set
    val spans = mutableListOf<PlaybackSpan>()
    val marks = mutableListOf<TimelineMark>()

    init { require(rate in 1..192000) }

    fun anchor(ns: Long, frame: Long) {
        if (ns <= 0 || frame < 0) return
        val point = Anchor(ns, frame)
        if (anchors.isEmpty() || ns >= anchors.last().ns) anchors.add(point)
        else {
            val i = anchors.binarySearchBy(ns) { it.ns }
            anchors.add(if (i >= 0) i else -i - 1, point)
        }
    }
    fun finish(frame: Long) { require(frame >= 0); captureEnd = frame }
    fun gate(action: String, captured: Long, held: Long, replay: Int) {
        require(held in 0..captured && replay.toLong() in 0..held)
        when (action) {
            "pause" -> { require(pendingGap == null); pendingGap = captured - held }
            "resume", "stop" -> {
                val start = requireNotNull(pendingGap)
                val end = start + held - replay
                require(end in start..captured && start >= (gaps.lastOrNull()?.end ?: 0L))
                gaps.add(Gap(start, end, (gaps.lastOrNull()?.totalOmitted ?: 0L) + end - start))
                pendingGap = null
            }
            else -> error("Unknown quiet transition")
        }
    }

    fun locate(mark: TimelineMark): MarkPosition = locate(mark.bootNs)

    fun locate(bootNs: Long): MarkPosition {
        if (anchors.isEmpty()) return MarkPosition.Unknown
        val index = anchors.binarySearchBy(bootNs) { it.ns }
        val next = if (index >= 0) index else -index - 1
        val before = anchors[(next - 1).coerceAtLeast(0)]
        val after = anchors[next.coerceAtMost(anchors.lastIndex)]
        val a = if (abs(before.ns - bootNs) <= abs(after.ns - bootNs)) before else after
        val frame = a.frame + ((bootNs - a.ns) * (rate / 1e9)).roundToLong()
        if (frame < 0 || frame >= (captureEnd ?: Long.MAX_VALUE) || frame >= (pendingGap ?: Long.MAX_VALUE))
            return MarkPosition.Unknown
        val gapIndex = gaps.binarySearchBy(frame) { it.start }
        val gap = gaps.getOrNull(if (gapIndex >= 0) gapIndex else -gapIndex - 2)
        if (gap != null && frame < gap.end) return MarkPosition.Omitted
        val mediaUs = (frame - (gap?.totalOmitted ?: 0L)) * 1_000_000L / rate
        val spanIndex = spans.binarySearchBy(mediaUs) { it.startUs }
        val span = spans.getOrNull(if (spanIndex >= 0) spanIndex else -spanIndex - 2) ?: return MarkPosition.Unknown
        if (mediaUs >= span.endUs) return MarkPosition.Unknown
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

internal class TimelineIndex {
    val runs = linkedMapOf<String, RunTimeline>()
    private var active: RunTimeline? = null
    fun accept(e: JSONObject) {
        when (e.getString("event")) {
            "run_started" -> {
                active = null
                val rate = e.optInt("sample_rate")
                if (rate !in 1..192000 || !e.has("elapsed_ms") || !e.has("wall_ms") || !e.has("run")) return
                val run = RunTimeline(rate, e.getLong("elapsed_ms"), e.getLong("wall_ms"))
                runs[e.getString("run")] = run
                active = run
            }
            "run_stopped", "error" -> {
                active?.endEvent = e.getString("event")
                if (e.has("wall_ms")) active?.endWallMs = e.getLong("wall_ms")
                active = null
            }
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
            "audio_context" -> {
                val span = runs[e.optString("run")]?.spans?.firstOrNull {
                    it.file == e.optString("file") && it.hash == e.optString("sha256")
                } ?: return
                ContextWindow.read(e, span)?.let { span.windows[it.startMs] = it }
            }
        }
    }
}

fun readTimeline(lines: Sequence<String>): List<RunTimeline> {
    val index = TimelineIndex()
    lines.forEach { index.accept(JSONObject(it)) }
    return index.runs.values.toList()
}

fun sessionTimeline(audio: File): List<RunTimeline> {
    val journal = File(audio.parentFile, TimelineJournal.FILE)
    return if (journal.isFile) journal.useLines { readTimeline(it) } else emptyList()
}
