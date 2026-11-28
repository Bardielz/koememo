package dev.koememo.phone

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

internal object SpeakerModel {
    const val ID = "pyannote3-eres2net-v1"
    const val SEG = "model.int8.onnx"
    const val EMB = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    val files = mapOf(SEG to "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d", EMB to "f1ee61be04000b50392a63442e5dd9feaea7e81dddd494d3647cf1c3d88a1498")
    fun directory(context: Context) = File(context.filesDir, ID)
}

internal class SpeakerAnalysis(dir: File) : AutoCloseable {
    private val embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, SpeakerModel.EMB).path, numThreads = 2)
    private val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(OfflineSpeakerSegmentationPyannoteModelConfig(File(dir, SpeakerModel.SEG).path), numThreads = 2),
        embedding = embedding))
    private val extractor = try { SpeakerEmbeddingExtractor(config = embedding) } catch (t: Throwable) { diarizer.release(); throw t }
    private val rate = diarizer.sampleRate().also { check(it == 16000) }

    fun analyze(clip: SessionClip, profiles: SpeakerProfiles, cancelled: () -> Boolean): List<SpeakerTurn> {
        check(!clip.hashMismatch) { "原音校验失败，请重新同步" }
        val turns = mutableListOf<SpeakerTurn>()
        val buffer = FloatArray(60 * rate)
        var used = 0
        var processedMs = 0
        fun batch() {
            if (cancelled()) throw CancellationException()
            if (used >= rate / 4) {
                val pcm = if (used == buffer.size) buffer else buffer.copyOf(used)
                val segments = diarizer.process(pcm)
                if (cancelled()) throw CancellationException()
                val local = segments.mapNotNull {
                    val start = (it.start * 1000).roundToInt().coerceIn(0, used / 16)
                    val end = (it.end * 1000).roundToInt().coerceIn(start, used / 16)
                    if (end > start) SpeakerTurn(start, end, it.speaker) else null
                }.sortedBy { it.start }
                val vectors = mutableMapOf<Int, FloatArray>()
                for (id in local.map { it.id }.distinct()) {
                    val clean = longestSolo(local, id) ?: continue
                    val start = clean.first * 16
                    val end = minOf(clean.second * 16, start + 8 * rate)
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(pcm.copyOfRange(start, end), rate)
                        stream.inputFinished()
                        if (extractor.isReady(stream)) vectors[id] = extractor.compute(stream)
                    } finally { stream.release() }
                }
                val ids = profiles.assign(vectors)
                local.forEach { turns.add(SpeakerTurn(processedMs + it.start, processedMs + it.end, ids[it.id] ?: 0)) }
            }
            processedMs += used / 16
            used = 0
        }
        RecordingDecoder.read(clip.file, clip.hash) { input, hz, count ->
            if (cancelled()) throw CancellationException()
            val outputCount = (count * rate.toLong() / hz).toInt()
            for (i in 0 until outputCount) {
                val start = (i.toLong() * hz / rate).toInt()
                val end = maxOf(start + 1, ((i + 1L) * hz / rate).toInt()).coerceAtMost(count)
                var sum = 0f
                for (n in start until end) sum += input[n]
                buffer[used++] = sum / (end - start)
                if (used == buffer.size) batch()
            }
        }
        if (used > 0) batch()
        return turns
    }
    override fun close() { try { extractor.release() } finally { diarizer.release() } }
}

/** Pick clean reference speech; don't enroll a voice from overlap alone. Times are milliseconds. */
internal fun longestSolo(turns: List<SpeakerTurn>, id: Int): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    fun consider(start: Int, end: Int) {
        if (end - start >= 650 && end - start > (best?.let { it.second - it.first } ?: 0)) best = start to end
    }
    for (turn in turns.filter { it.id == id }) {
        var start = turn.start
        for (other in turns) {
            if (other.start >= turn.end) break
            if (other.id == id || other.end <= start) continue
            consider(start, other.start)
            start = maxOf(start, other.end)
        }
        consider(start, turn.end)
    }
    return best
}
