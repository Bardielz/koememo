package dev.koememo.phone

import android.content.Context
import android.util.AtomicFile
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal class LocalTranscription(context: Context, val file: File) {
    private val model = AsrModel.directory(context)
    private val key = MessageDigest.getInstance("SHA-256").apply {
        file.inputStream().buffered().use { source ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = source.read(bytes)
                if (n < 0) break
                update(bytes, 0, n)
            }
        }
    }.digest().hex()
    private val store = AtomicFile(File(context.filesDir, "transcripts/${AsrModel.ID}-15s-v1-$key.json"))
    val data = try {
        JSONObject(store.openRead().bufferedReader().use { it.readText() })
    } catch (_: java.io.FileNotFoundException) {
        JSONObject().put("model", AsrModel.ID).put("sha256", key).put("windows", JSONArray())
    }
    data class SavedWindow(val count: Int, val startMs: Long, val text: String)

    fun run(cancelled: () -> Boolean, update: (SavedWindow) -> Unit) {
        if (data.optBoolean("complete")) return
        val windows = data.getJSONArray("windows")
        val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(File(model, "model.int8.onnx").path, "auto", true),
            tokens = File(model, "tokens.txt").path, numThreads = 2, provider = "cpu"))
        val recognizer = OfflineRecognizer(config = config)
        try {
            var index = 0
            var startMs = 0L
            RecordingDecoder.read(file) { samples, rate, count ->
                if (cancelled()) throw CancellationException()
                val endMs = startMs + count * 1000L / rate
                if (index >= windows.length()) {
                    val stream = recognizer.createStream()
                    val text = try {
                        stream.acceptWaveform(samples, rate)
                        recognizer.decode(stream)
                        recognizer.getResult(stream).text.trim()
                    } finally { stream.release() }
                    windows.put(JSONObject().put("start_ms", startMs).put("end_ms", endMs).put("text", text))
                    save()
                    update(SavedWindow(windows.length(), startMs, text))
                }
                startMs = endMs
                index++
            }
            check(windows.length() > 0) { "文件没有可解码的音频" }
            data.put("complete", true)
            save()
        } finally { recognizer.release() }
    }
    private fun save() {
        val out = store.startWrite()
        try { out.write(data.toString().toByteArray()); store.finishWrite(out) }
        catch (error: Exception) { store.failWrite(out); throw error }
    }
}
