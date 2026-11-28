package dev.koememo.phone

import android.content.Context
import android.util.AtomicFile
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

// Readers and writers share the lock because AtomicFile.openRead may recover an interrupted write.
private val transcriptFiles = Any()

internal fun readJson(file: AtomicFile, initial: () -> JSONObject): JSONObject = synchronized(transcriptFiles) {
    try { file.openRead().bufferedReader().use { JSONObject(it.readText()) } }
    catch (_: java.io.FileNotFoundException) { initial() }
}

internal fun writeJson(file: AtomicFile, json: JSONObject) = synchronized(transcriptFiles) {
    val directory = requireNotNull(file.baseFile.parentFile)
    check(directory.isDirectory || directory.mkdirs()) { "无法创建文字稿目录" }
    val out = file.startWrite()
    try { out.write(json.toString().toByteArray()); file.finishWrite(out) }
    catch (error: Exception) { file.failWrite(out); throw error }
}

internal class LocalTranscription(context: Context, val clip: SessionClip) {
    private val store = AtomicFile(File(context.filesDir, "transcripts/${AsrModel.ID}-15s-v1-${clip.hash}.json"))
    private val source = clip.file.canonicalFile.toPath().let { path ->
        val root = File(context.filesDir, "audio").canonicalFile.toPath()
        require(path.startsWith(root)) { "找不到原音来源" }
        root.relativize(path).toString()
    }
    private val edits = AtomicFile(File(context.filesDir, "transcript-edits/$source.${clip.hash}.${AsrModel.ID}-15s-v1.json"))
    fun snapshot() = readJson(store) {
        JSONObject().put("model", AsrModel.ID).put("sha256", clip.hash).put("windows", JSONArray())
    }
    fun corrections() = readJson(edits, ::JSONObject)
    fun correct(startMs: Int, replacement: String?) = synchronized(transcriptFiles) {
        val data = corrections()
        if (replacement == null) data.remove(startMs.toString()) else data.put(startMs.toString(), replacement)
        writeJson(edits, data)
    }

    fun run(data: JSONObject, recognizer: OfflineRecognizer, cancelled: () -> Boolean, saved: () -> Unit) {
        val windows = data.getJSONArray("windows")
        var index = 0
        var startMs = 0L
        RecordingDecoder.read(clip.file, clip.hash) { samples, rate, count ->
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
                writeJson(store, data)
                saved()
            }
            startMs = endMs
            index++
        }
        check(windows.length() > 0) { "文件没有可解码的音频" }
        data.put("complete", true)
        writeJson(store, data)
    }
    companion object {
        fun recognizer(context: Context): OfflineRecognizer {
            val model = AsrModel.directory(context)
            return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(File(model, "model.int8.onnx").path, "auto", true),
                tokens = File(model, "tokens.txt").path, numThreads = 2, provider = "cpu")))
        }
    }
}
