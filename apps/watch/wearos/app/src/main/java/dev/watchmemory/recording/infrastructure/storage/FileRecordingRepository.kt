package dev.watchmemory.recording.infrastructure.storage

import android.content.Context
import android.os.SystemClock
import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.domain.SessionId
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class FileRecordingRepository(context: Context) : RecordingRepository {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "recordings")
    override fun load() = SavedRecording(prefs.getString("session", null), prefs.getBoolean("recording", false), prefs.getString("status", "准备记录") ?: "准备记录")
    override fun save(value: SavedRecording) {
        val edit = prefs.edit().putBoolean("recording", value.recording).putString("status", value.status)
        if (value.sessionId == null) edit.remove("session") else edit.putString("session", value.sessionId)
        check(edit.commit()) { "Cannot persist recording session" }
    }
    override fun createSession() = SessionId(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8))
    fun directory(id: SessionId) = File(root, id.value)
    override fun prepare(id: SessionId) { val file = directory(id); check(file.isDirectory || file.mkdirs()) { "Cannot create recording directory" } }
    @Synchronized override fun append(id: SessionId, event: String, fields: Map<String, Any>) {
        val data = JSONObject().put("event", event).put("wall_ms", System.currentTimeMillis()).put("elapsed_ms", SystemClock.elapsedRealtime())
        fields.forEach { (key, value) -> data.put(key, value) }
        FileOutputStream(File(directory(id), "events.jsonl"), true).use { it.write((data.toString() + "\n").toByteArray(Charsets.UTF_8)) }
    }
    override fun library(): RecordingLibrary {
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("aac", "ogg") }.toList()
        return RecordingLibrary(files.size, files.sumOf { it.length() }, files.maxByOrNull { it.lastModified() }?.absolutePath)
    }
}
