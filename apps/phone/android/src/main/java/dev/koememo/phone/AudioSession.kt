package dev.koememo.phone

import dev.koememo.sync.PlaybackSpan
import dev.koememo.sync.RunTimeline
import dev.koememo.sync.TimelineJournal
import dev.koememo.sync.readTimeline
import dev.koememo.sync.sha256
import java.io.File

internal fun File.isAudioSegment() = isFile && extension in setOf("aac", "ogg")

internal fun localSegments(directory: File): List<File> =
    checkNotNull(directory.listFiles()) { "无法读取会话目录" }.filter { it.isAudioSegment() }.sortedBy { it.name }

internal data class SessionClip(val file: File, val hash: String, val run: RunTimeline?, val span: PlaybackSpan?, val hashMismatch: Boolean)

internal class AudioSession(val runs: List<RunTimeline>, val clips: List<SessionClip>, val timelineError: Boolean, val catalog: SyncCatalog?) {
    val received = clips.count { catalog?.hashes?.get(it.file.name) == it.hash }
    val mapped = clips.filter { it.span != null && !it.hashMismatch }
    private val localNames = clips.map { it.file.name }.toSet()
    val missing = runs.sumOf { run -> run.spans.count { it.file !in localNames } }
    val mappedUs = mapped.sumOf { clip -> clip.span!!.let { (it.endUs - it.startUs).coerceAtLeast(0) } }
    val omittedUs = runs.sumOf { it.omittedUs }

    // Only quantify missing local media where a whole capture run has a final sample count.
    // Clip the union to the captured range so codec padding never covers an earlier hole.
    val uncoveredUs: Long? = if (runs.isEmpty() || runs.any { it.captureUs == null || it.openOmission } ||
        clips.any { it.span == null }) null else runs.sumOf { run ->
        val retained = (run.captureUs!! - run.omittedUs).coerceAtLeast(0)
        var end = 0L
        var covered = 0L
        for (span in mapped.filter { it.run === run }.mapNotNull { it.span }.sortedBy { it.startUs }) {
            val from = span.startUs.coerceIn(0, retained)
            val to = span.endUs.coerceIn(from, retained)
            covered += (to - maxOf(from, end)).coerceAtLeast(0)
            end = maxOf(end, to)
        }
        retained - covered
    }

    companion object {
        fun load(directory: File, onlyFile: File? = null): AudioSession {
            val journal = File(directory, TimelineJournal.FILE)
            val result = runCatching { if (journal.isFile) journal.useLines { readTimeline(it) } else emptyList() }
            val runs = result.getOrDefault(emptyList())
            val catalog = runCatching { SyncCatalog.read(directory) }.getOrNull()
            val spans = runs.flatMap { run -> run.spans.map { span -> span.file to (run to span) } }.toMap()
            val files = (if (onlyFile == null) localSegments(directory)
                else listOf(onlyFile).filter { it.parentFile == directory && it.isAudioSegment() }).associateBy { it.name }
            val ordered = (spans.keys + files.keys).mapNotNull(files::get)
            val clips = ordered.map { file ->
                val match = spans[file.name]
                val hash = file.sha256()
                val expected = catalog?.hashes?.get(file.name) ?: match?.second?.hash
                val located = match?.takeIf { it.second.hash == hash }
                SessionClip(file, hash, located?.first, located?.second, expected != null && expected != hash)
            }
            return AudioSession(runs, clips, result.isFailure, catalog)
        }
    }
}
