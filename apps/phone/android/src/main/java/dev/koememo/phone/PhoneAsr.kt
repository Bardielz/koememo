package dev.koememo.phone

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AsrFamily(val label: String, val modelId: String, val encoder: String = "", val decoder: String = "",
                              private val encoderSha: String = "", private val decoderSha: String = "") {
    SENSE("SenseVoice", AsrModel.ID),
    FUN("Fun-ASR-Nano-2512", "fun-asr-nano-2512-q8", "funasr-encoder-f16.gguf", "qwen3-0.6b-q8_0.gguf",
        "f92f91d01a24fbed6c863495b2ee8c6a6788144a02858b75743f0946668de8a2", "819f385dc0e035dccc3d9e7edaf6b7b044b8ba7ace63cbcbf84c7e397eecbf27"),
    QWEN("Qwen3-ASR-1.7B", "qwen3-asr-1.7b-q8", "mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", "Qwen3-ASR-1.7B-Q8_0.gguf",
        "24eaf9a96fa94fe36ed0c1de68d44cccdb3f2229ee614e5b1b5bab951bc37883", "cd5f5ce9d7c9c03ff47d54d7f2726e63a39e61e3001640c5d08b6e4d3aed1e6d");

    fun directory(context: Context) = File(context.filesDir, modelId)
    fun install(context: Context, tree: Uri) {
        if (this == SENSE) AsrModel.import(context, tree)
        else importModel(context, tree, modelId, mapOf(encoder to encoderSha, decoder to decoderSha))
    }
}

internal enum class AsrProcessor(val label: String, val device: String) {
    CPU("CPU", "CPU"), GPU("GPU · Adreno OpenCL", "GPUOpenCL"), NPU("NPU · Hexagon", "HTP0")
}

internal data class AsrChoice(val family: AsrFamily, val processor: AsrProcessor) {
    val key get() = if (family == AsrFamily.SENSE) AsrModel.ID else family.modelId + "-" + processor.name.lowercase()
    val label get() = family.label + " · " + processor.label
    companion object {
        val legacy = AsrChoice(AsrFamily.SENSE, AsrProcessor.CPU)
        val choices = listOf(legacy) + listOf(AsrFamily.FUN, AsrFamily.QWEN).flatMap { family ->
            AsrProcessor.entries.map { AsrChoice(family, it) }
        }
        fun from(key: String?) = if (key == null) legacy else choices.first { it.key == key }
    }
}

internal interface PhoneRecognizer : Closeable {
    fun transcribe(samples: FloatArray, rate: Int): String
}

internal fun openRecognizer(context: Context, choice: AsrChoice): PhoneRecognizer {
    if (choice.family != AsrFamily.SENSE) return NativeAsr(context, choice)
    val engine = LocalTranscription.recognizer(context)
    return object : PhoneRecognizer {
        override fun transcribe(samples: FloatArray, rate: Int): String {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, rate)
                engine.decode(stream)
                return engine.getResult(stream).text.trim()
            } finally { stream.release() }
        }
        override fun close() = engine.release()
    }
}

private class NativeAsr(context: Context, private val choice: AsrChoice) : PhoneRecognizer {
    private val process: Process
    private val reader: DataInputStream
    private val writer: DataOutputStream
    @Volatile private var diagnostic = ""
    init {
        val lib = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(lib, "libkoe_asr.so")
        check(binary.canExecute()) { "当前设备未安装 ARM64 识别运行库" }
        val family = choice.family
        val model = family.directory(context)
        val builder = ProcessBuilder(binary.path, family.name.lowercase(), choice.processor.device,
            File(model, family.encoder).path, File(model, family.decoder).path)
        builder.directory(context.cacheDir)
        builder.environment()["LD_LIBRARY_PATH"] = lib.path + ":/vendor/lib64:/vendor/lib64/egl"
        builder.environment()["ADSP_LIBRARY_PATH"] = lib.path + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp"
        process = builder.start()
        reader = DataInputStream(process.inputStream.buffered())
        writer = DataOutputStream(process.outputStream.buffered())
        kotlin.concurrent.thread(isDaemon = true, name = "ASR diagnostics") {
            runCatching { process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.startsWith("KoeMemo ASR:")) diagnostic = line.take(180) }
            } }
        }
        try { check(receive() == "ready") { "模型初始化失败" } }
        catch (e: Exception) { close(); throw e }
    }
    private fun receive(): String = try {
        val size = reader.readInt()
        check(size in 0..65536) { "识别进程返回了无效的结果长度" }
        val bytes = ByteArray(size)
        reader.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    } catch (e: java.io.IOException) {
        throw IllegalStateException("${choice.label} 推理进程已退出 ${diagnostic}", e)
    }
    override fun transcribe(samples: FloatArray, rate: Int): String {
        val pcm = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        pcm.asFloatBuffer().put(samples)
        writer.writeInt(rate)
        writer.writeInt(samples.size)
        writer.write(pcm.array())
        writer.flush()
        val text = receive()
        return (if (choice.family == AsrFamily.QWEN) text.substringAfter("<asr_text>") else text).trim()
    }
    override fun close() {
        runCatching { writer.close() }
        process.destroy()
        runCatching { reader.close() }
    }
}
