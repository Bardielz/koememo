package dev.watchmemory

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import dev.koememo.sync.*
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
        var watchdog: java.util.TimerTask? = null
        fun deadline() {
            watchdog?.cancel()
            watchdog = object : java.util.TimerTask() {
                override fun run() { client.close(channel) }
            }.also { timer.schedule(it, 180000L) }
            timer.purge()
        }
        try {
            deadline()
            require(channel.path == CHANNEL)
            val output = DataOutputStream(Tasks.await(client.getOutputStream(channel)).buffered())
            val input = DataInputStream(Tasks.await(client.getInputStream(channel)).buffered())
            val entries = catalog()
            output.writeInt(entries.size)
            entries.forEach { it.first.send(output) }; output.flush()
            while (true) {
                deadline()
                val index = input.readInt()
                if (index == -1) { output.writeBoolean(true); output.flush(); break }
                val (entry,file) = entries[index]
                val offset = input.readLong()
                require(offset in 0..entry.size)
                file.inputStream().use { source ->
                    source.channel.position(offset)
                    copyExactly(source, output, entry.size-offset)
                }
                output.flush()
                require(input.readUTF() == entry.hash)
                check(getSharedPreferences("sync_receipts", MODE_PRIVATE).edit()
                    .putBoolean(channel.nodeId + "/" + entry.hash, true).commit())
            }
        } catch (e: Exception) {
            android.util.Log.w("KoeMemoSync","Transfer ended", e)
        } finally { timer.cancel(); client.close(channel) }
    }
}
