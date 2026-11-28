package dev.watchmemory.transfer

import android.content.Context
import dev.koememo.sync.*
import dev.koememo.telemetry.SyncMetrics
import dev.koememo.telemetry.TelemetrySource
import org.json.JSONObject
import java.io.*

class AudioTransferSession(private val context: Context, private val diagnosticsEnabled: Boolean,
    private val telemetry: TelemetrySource) {
    private fun catalog(): List<Pair<Segment,File>> = buildList {
        for (dir in File(context.filesDir, "recordings").listFiles().orEmpty().sortedBy { it.name }) {
            val events = File(dir, "events.jsonl")
            if (!events.isFile) continue
            events.useLines { lines -> lines.forEach { line ->
                if (!line.contains("segment_closed")) return@forEach
                val event = runCatching { JSONObject(line) }.getOrNull()
                if (event?.optString("event") == "segment_closed") {
                    val entry = Segment(dir.name, event.getString("file"), event.getLong("bytes"), event.getString("sha256"))
                    val file = File(dir, entry.name)
                    if (file.isFile && file.length() == entry.size) add(entry to file)
                }
            } }
        }
    }
    private fun sendTimelines(output: DataOutputStream, entries: List<Pair<Segment, File>>, progress: TransferProgress) {
        val sessions = entries.groupBy { it.first.session }
        output.writeInt(sessions.size)
        for ((id, segments) in sessions) {
            val catalog = segments.associate { it.first.name to it.first.hash }
            output.writeUTF(id)
            val journal = File(segments.first().second.parentFile, "events.jsonl")
            journal.useLines { lines ->
                val iterator = lines.iterator()
                val projected = iterator.asSequence().mapNotNull { line ->
                    // Do not parse large diagnostic/sensor payloads just to discard them.
                    if (!timelineEvent.containsMatchIn(line)) return@mapNotNull null
                    val event = try { JSONObject(line) } catch (e: org.json.JSONException) {
                        // A writer can still be appending the last line during a live sync.
                        if (iterator.hasNext()) throw e
                        return@mapNotNull null
                    }
                    if (event.optString("event") == "segment_closed" &&
                        catalog[event.optString("file")] != event.optString("sha256")) return@mapNotNull null
                    TimelineJournal.project(event)?.toString()
                }
                TimelineTransfer.write(output, projected) { progress.advanced(0) }
            }
        }
        output.flush()
    }
    private val timelineEvent = Regex(""""event"\s*:\s*"(run_started|audio_configuration|quiet_gate|marker|segment_closed|run_stopped|error)"""")

    fun serve(channel: TransferChannel) {
        val timer = java.util.Timer(true)
        val progress = TransferProgress(android.os.SystemClock::elapsedRealtime)
        val withTimeline = channel.path.startsWith("$TIMELINE_CHANNEL/")
        val withReceipts = withTimeline || channel.path.startsWith("$RECEIPTS_CHANNEL/")
        val prefix = when { withTimeline -> TIMELINE_CHANNEL; withReceipts -> RECEIPTS_CHANNEL; else -> CHANNEL }
        val session = channel.path.removePrefix("$prefix/").takeIf {
            it.matches(Regex("[0-9a-f-]{36}"))
        }
        val metrics = if (diagnosticsEnabled)
            SyncMetrics(context, "watch", session ?: "legacy", telemetry) else null
        var outcome = "interrupted"
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        fun details() = "session=${session ?: "legacy"} side=watch stage=${progress.stage} segment=${progress.segment} bytes=${progress.bytes}"
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (progress.expired(180_000L)) {
                    android.util.Log.w("KoeMemoSync", "${details()} reason=idle_timeout")
                    timedOut.set(true)
                    cancel()
                    channel.close()
                }
            }
        }, 5_000L, 5_000L)
        try {
            require(channel.path == CHANNEL || session != null && channel.path == "$prefix/$session")
            val output = DataOutputStream(channel.output().buffered())
            val input = DataInputStream(channel.input().buffered())
            metrics?.enter("catalog")
            progress.enter("catalog")
            val entries = catalog()
            output.writeInt(entries.size)
            val receipts = context.getSharedPreferences("sync_receipts", Context.MODE_PRIVATE)
            entries.forEach { (entry, _) ->
                entry.send(output)
                if (withReceipts) output.writeBoolean(receipts.getBoolean(channel.nodeId + "/" + entry.hash, false))
            }
            output.flush()
            while (true) {
                metrics?.enter("request")
                progress.enter("request")
                val index = input.readInt()
                if (index == -1) {
                    if (withTimeline) {
                        progress.enter("timeline")
                        sendTimelines(output, entries, progress)
                        require(input.readBoolean()) { "时间轴未确认" }
                    }
                    output.writeBoolean(true)
                    output.flush()
                    break
                }
                val (entry,file) = entries[index]
                val offset = input.readLong()
                require(offset in 0..entry.size)
                metrics?.enter("send")
                progress.enter("send", entry.name, offset)
                file.inputStream().use { source ->
                    source.channel.position(offset)
                    copyExactly(source, output, entry.size-offset) { count ->
                        progress.advanced(count)
                        metrics?.copied(count)
                    }
                }
                output.flush()
                metrics?.enter("ack")
                progress.enter("ack")
                require(input.readUTF() == entry.hash)
                check(receipts.edit()
                    .putBoolean(channel.nodeId + "/" + entry.hash, true).commit())
                metrics?.confirmed(offset, entry.size)
            }
            outcome = "completed"
            android.util.Log.i("KoeMemoSync", "${details()} reason=completed")
        } catch (e: Exception) {
            outcome = e.javaClass.simpleName
            android.util.Log.w("KoeMemoSync","${details()} reason=${e.javaClass.simpleName}", e)
        } finally {
            timer.cancel()
            try { channel.close() } finally {
                metrics?.finish(if (timedOut.get()) "idle_timeout" else outcome)
            }
        }
    }
}
