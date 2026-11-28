package dev.koememo.phone

import android.media.MediaMetadataRetriever
import dev.koememo.sync.PlaybackSpan
import dev.koememo.sync.TimelineJournal
import java.io.File

internal data class AudioTrack(val file: File, val durationMs: Long?, val span: PlaybackSpan?)
internal data class AudioPosition(val index: Int, val offsetMs: Int)

internal class SessionPlayback(val tracks: List<AudioTrack>, val note: String = "") {
    private val starts = tracks.runningFold(0L) { total, track -> total + (track.durationMs ?: 0) }
    val totalMs = starts.last().takeIf { it > 0 && tracks.all { track -> (track.durationMs ?: 0) > 0 } }

    fun elapsed(index: Int, offset: Int) = starts[index] + offset

    fun locate(elapsed: Long): AudioPosition? {
        val total = totalMs ?: return null
        val ms = elapsed.coerceIn(0, total)
        val match = starts.binarySearch(ms)
        val index = (if (match < 0) -match - 2 else match).coerceAtMost(tracks.lastIndex)
        return AudioPosition(index, (ms - starts[index]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    companion object {
        fun load(dir: File): SessionPlayback {
            val session = if (File(dir, TimelineJournal.FILE).exists() || File(dir, SyncCatalog.FILE).exists())
                AudioSession.load(dir) else null
            val tracks = session?.clips?.filterNot { it.hashMismatch }?.map { track(it.file, it.span) }
                ?: localSegments(dir).map { track(it, null) }
            val notes = buildList {
                if (tracks.size > 1 && tracks.any { it.span == null }) add("缺少完整录制时间，部分先后顺序无法确认。")
                if (session?.clips?.any { it.hashMismatch } == true) add("部分原音校验失败，已跳过。")
                if ((session?.missing ?: 0) > 0 || session?.catalog?.hashes?.keys.orEmpty().any { key -> tracks.none { it.file.name == key } })
                    add("部分原音尚不可用。")
                if ((session?.omittedUs ?: 0) > 0) add("回听会跳过已省略的静音。")
            }
            return SessionPlayback(tracks, notes.joinToString("\n"))
        }

        private fun track(file: File, span: PlaybackSpan?): AudioTrack {
            val ms = runCatching {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally { reader.release() }
            }.getOrNull()?.takeIf { it > 0 } ?: span?.let { (it.endUs - it.startUs) / 1000 }?.takeIf { it > 0 }
            return AudioTrack(file, ms, span)
        }
    }
}
