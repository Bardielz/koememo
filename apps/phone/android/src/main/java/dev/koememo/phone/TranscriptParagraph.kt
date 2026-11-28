package dev.koememo.phone

import android.content.Context
import org.json.JSONObject

internal data class TranscriptLine(val clip: SessionClip, val start: Int, val end: Int, val original: String,
                                   val edit: String?, val speakers: List<Int>, val speaker: String,
                                   val wall: Long?, val until: Long?, val context: String,
                                   val speechStart: Int? = null, val speechEnd: Int? = null) {
    val text get() = edit ?: original
}

internal fun transcriptLines(context: Context, clip: SessionClip, choice: AsrChoice, analysis: JSONObject,
                            names: JSONObject, snapshot: JSONObject): List<TranscriptLine> {
    val edits = LocalTranscription(context, clip, choice).corrections()
    val turns = SpeakerStore.turns(analysis, clip)
    val windows = snapshot.getJSONArray("windows")
    return buildList {
        for (i in 0 until windows.length()) {
            val row = windows.getJSONObject(i)
            val start = row.getInt("start_ms")
            val end = row.optInt("end_ms", start + 15000)
            val original = row.getString("text")
            val edit = edits.optString(start.toString()).takeIf { edits.has(start.toString()) }
            if ((edit ?: original).isBlank() && edit == null) continue
            val overlaps = turns.orEmpty().filter { it.end > start && it.start < end }
            val ids = overlaps.map { it.id }.distinct().sorted()
            val speaker = turns?.let { SpeakerStore.label(it, start, end, names) } ?: "未区分发言人"
            val span = clip.span?.takeUnless { clip.hashMismatch }
            fun wall(ms: Int) = span?.let { clip.run?.timeAt(it.file, ms.toLong()) }
            add(TranscriptLine(clip, start, end, original, edit, ids, speaker, wall(start), wall(end),
                span?.contextAt(start)?.description().orEmpty(),
                overlaps.minOfOrNull { maxOf(it.start,start) }, overlaps.maxOfOrNull { minOf(it.end,end) }))
        }
    }
}

internal fun transcriptParagraphs(lines: List<TranscriptLine>): List<List<TranscriptLine>> {
    val result = mutableListOf<MutableList<TranscriptLine>>()
    var length = 0
    for (line in lines) {
        val group = result.lastOrNull()
        val prev = group?.lastOrNull()
        val gap = when {
            line.wall != null && prev?.until != null -> line.wall - prev.until
            line.clip.file == prev?.clip?.file -> (line.start - prev.end).toLong()
            else -> Long.MAX_VALUE
        }
        val voiceGap = if (prev != null && prev.clip.file == line.clip.file && prev.speechEnd != null && line.speechStart != null)
            line.speechStart - prev.speechEnd else 0
        val boundary = voiceGap >= 2000 || prev == null || line.speakers != prev.speakers || line.speaker != prev.speaker ||
            line.speakers.size > 1 || gap !in -250L..1999L || length + line.text.length > 380 ||
            (length >= 120 && prev.text.trimEnd().lastOrNull() in "。！？.!?".toList())
        if (boundary) { result.add(mutableListOf(line)); length = 0 } else group!!.add(line)
        length += line.text.length
    }
    return result
}

internal fun paragraphSeparator(left: String, right: String): String {
    val a = left.lastOrNull() ?: return ""
    val b = right.firstOrNull() ?: return ""
    return if (a.code < 256 && b.code < 256 && !a.isWhitespace() && !b.isWhitespace() &&
        (b.isLetterOrDigit() || b == '"')) " " else ""
}

internal fun sourceTime(ms: Int): String {
    val sec = ms / 1000
    return "%d:%02d:%02d".format(java.util.Locale.ROOT, sec / 3600, sec / 60 % 60, sec % 60)
}
