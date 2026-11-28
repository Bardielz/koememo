package dev.koememo.phone

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal enum class TranscriptModel(val title: String, val filename: String, val sha: String) {
    MEETING("Meeting Agent 中文", "gemma-4-E2B-meeting-agent-zh-Q4_0.gguf", "560041008644c58501e28af80da46ecfdae250381442d1784e0d016dd499946c"),
    BASE("Gemma 4 E2B 原版", "gemma-4-E2B_q4_0-it.gguf", "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634"),
    EMBED1("EmbeddingGemma 1", "embeddinggemma-300M-Q8_0.gguf", "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63"),
    EMBED2("EmbeddingGemma 2", "embeddinggemma-2-Q8_0.gguf", "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135");
    val embedding get() = this == EMBED1 || this == EMBED2
    private val directory get() = "transcript-model-" + sha.take(16)
    fun file(context: Context) = File(File(context.filesDir, directory), filename)
    fun install(context: Context, tree: Uri) {
        val files = mutableMapOf(filename to sha)
        if (this == MEETING) files["system_prompt.txt"] = "11e5e1dcc358cc62f47a7d8185676b1850d6dd204b8005648fc116236a4914ef"
        importModel(context, tree, directory, files)
    }
}

// The child only reads local files. Closing it also interrupts a blocked inference read.
internal class TranscriptWorker(context: Context, val model: TranscriptModel) : Closeable {
    private val lib = File(context.applicationInfo.nativeLibraryDir)
    private val process = ProcessBuilder(File(lib, "libkoe_transcript.so").path,
        if (model.embedding) "embed" else "notes", model.file(context).path).apply {
        directory(context.cacheDir)
        environment()["LD_LIBRARY_PATH"] = lib.path
        // These signed libraries keep their upstream SONAMEs; preload them only in this
        // worker so the ASR worker can still select its Snapdragon GPU and NPU backends.
        environment()["LD_PRELOAD"] = listOf("ggml-base", "ggml", "llama").joinToString(":") {
            File(lib, "libkoe_portable_$it.so").path
        }
        environment()["KOE_CPU_BACKEND"] = File(lib, "libkoe_portable_ggml-cpu-android_armv8.0_1.so").path
        redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
    }.start()
    private val reader = DataInputStream(process.inputStream.buffered())
    private val writer = DataOutputStream(process.outputStream.buffered())
    private var ready = false
    private fun receive(): ByteArray {
        val n = reader.readInt()
        require(n in 0..65536) { "模型返回无效长度" }
        return ByteArray(n).also(reader::readFully)
    }
    fun run(text: String): ByteArray {
        if (!ready) {
            check(receive().decodeToString() == "ready") { "模型初始化失败" }
            ready = true
        }
        val bytes = text.encodeToByteArray()
        require(bytes.size in 1..65536) { "文字超过单次输入范围" }
        writer.writeInt(bytes.size); writer.write(bytes); writer.flush()
        return receive()
    }
    override fun close() {
        process.destroyForcibly()
        runCatching { writer.close() }; runCatching { reader.close() }
        runCatching { process.waitFor() }
    }
}

internal fun stableDigest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).hex()

internal fun embeddingVector(bytes: ByteArray): FloatArray {
    require(bytes.size == 768 * 4) { "向量尺寸不匹配" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(768).also {
        buffer.get(it)
        require(it.all(Float::isFinite) && it.sumOf { v -> v.toDouble() * v } in 0.99..1.01) { "向量无效" }
    }
}

internal fun meetingPrompt(system: String, lines: List<TranscriptLine>): String {
    val transcript = lines.joinToString("\n") { "[${sourceTime(it.start)}] ${it.speaker}　${it.text}" }
    // Escape delimiter-looking content in a recording before parsing template tokens.
    return "<|turn>system\n${system.trim()}<turn|>\n<|turn>user\n" +
        transcript.replace("<", "＜") + "<turn|>\n<|turn>model\n"
}

internal data class MeetingNote(val start: Int, val category: String, val body: String)
internal fun meetingNotes(raw: String, lines: List<TranscriptLine>): List<MeetingNote> {
    val times = lines.associate { sourceTime(it.start) to it.start }
    val pattern = Regex("""NOTE\s+\[([^\]]+)]\s*\((DECISION|ACTION|NUMBER|OPEN-ISSUE|-)\)\s*(.+)""")
    val actions = raw.trim().lines().filter { it.isNotBlank() }.map(String::trim)
    require(actions.lastOrNull() == "NEXT") { "缺少结束标记，请核对模型原始输出" }
    return actions.dropLast(1).map { action ->
        val m = requireNotNull(pattern.matchEntire(action)) { "动作格式无法验证" }
        MeetingNote(requireNotNull(times[m.groupValues[1]]) { "引用时间未出现在输入中" }, m.groupValues[2], m.groupValues[3])
    }
}
