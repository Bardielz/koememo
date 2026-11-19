package dev.watchmemory

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import dev.koememo.sync.*
import dev.koememo.telemetry.SyncMetrics
import org.json.JSONObject
import java.io.*

class AudioTransferService : WearableListenerService() {
    private fun catalog(): List<Pair<Segment,File>> = buildList {
        for (dir in File(filesDir, "recordings").listFiles().orEmpty().sortedBy { it.name }) {
            val events = File(dir, "events.jsonl")
            if (!events.isFile) continue
            events.useLines { lines -> lines.forEach { line ->
                val event = runCatching { JSONObject(line) }.getOrNull()
                if (event?.optString("event") == "segment_closed") {
                    val entry = Segment(dir.name, event.getString("file"), event.getLong("bytes"), event.getString("sha256"))
                    val file = File(dir, entry.name)
                    if (file.isFile && file.length() == entry.size) add(entry to file)
                }
            } }
        }
    }
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        val timer = java.util.Timer(true)
        val progress = TransferProgress(android.os.SystemClock::elapsedRealtime)
        val session = channel.path.removePrefix("$CHANNEL/").takeIf {
            it.matches(Regex("[0-9a-f-]{36}"))
        }
        val metrics = SyncMetrics(this, "watch", session ?: "legacy")
        var outcome = "interrupted"
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        fun details() = "session=${session ?: "legacy"} side=watch stage=${progress.stage} segment=${progress.segment} bytes=${progress.bytes}"
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (progress.expired(180_000L)) {
                    android.util.Log.w("KoeMemoSync", "${details()} reason=idle_timeout")
                    timedOut.set(true)
                    cancel()
                    client.close(channel)
                }
            }
        }, 5_000L, 5_000L)
        try {
            require(channel.path == CHANNEL || session != null && channel.path == "$CHANNEL/$session")
            val output = DataOutputStream(Tasks.await(client.getOutputStream(channel)).buffered())
            val input = DataInputStream(Tasks.await(client.getInputStream(channel)).buffered())
            metrics.enter("catalog")
            progress.enter("catalog")
            val entries = catalog()
            output.writeInt(entries.size)
            entries.forEach { it.first.send(output) }; output.flush()
            while (true) {
                metrics.enter("request")
                progress.enter("request")
                val index = input.readInt()
                if (index == -1) { output.writeBoolean(true); output.flush(); break }
                val (entry,file) = entries[index]
                val offset = input.readLong()
                require(offset in 0..entry.size)
                metrics.enter("send")
                progress.enter("send", entry.name, offset)
                file.inputStream().use { source ->
                    source.channel.position(offset)
                    copyExactly(source, output, entry.size-offset) { count ->
                        progress.advanced(count)
                        metrics.copied(count)
                    }
                }
                output.flush()
                metrics.enter("ack")
                progress.enter("ack")
                require(input.readUTF() == entry.hash)
                check(getSharedPreferences("sync_receipts", MODE_PRIVATE).edit()
                    .putBoolean(channel.nodeId + "/" + entry.hash, true).commit())
                metrics.confirmed(offset, entry.size)
            }
            outcome = "completed"
            android.util.Log.i("KoeMemoSync", "${details()} reason=completed")
        } catch (e: Exception) {
            outcome = e.javaClass.simpleName
            android.util.Log.w("KoeMemoSync","${details()} reason=${e.javaClass.simpleName}", e)
        } finally {
            timer.cancel()
            try { client.close(channel) } finally {
                metrics.finish(if (timedOut.get()) "idle_timeout" else outcome)
            }
        }
    }
}
