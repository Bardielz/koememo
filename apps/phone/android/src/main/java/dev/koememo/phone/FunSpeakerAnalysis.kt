package dev.koememo.phone

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.util.concurrent.CancellationException
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.apache.commons.math3.random.JDKRandomGenerator
import kotlin.math.abs
import kotlin.math.sqrt

internal object FunVad {
    init { System.loadLibrary("koe_fun_vad") }
    external fun detect(model: String, samples: FloatArray): IntArray
}

internal class FunSpeakerAnalysis(dir: File) : SpeakerEngine {
    private val vad = File(dir, "fsmn-vad.gguf").path
    private val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
        model = File(dir, "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx").path,
        numThreads = 2, provider = "cpu"))

    override fun window(pcm: FloatArray, offset: Int, profiles: SpeakerProfiles, cancelled: () -> Boolean): List<SpeakerTurn> {
        val times = FunVad.detect(vad, pcm)
        val chunks = mutableListOf<SpeakerTurn>()
        val embeddings = mutableListOf<FloatArray>()
        for (i in times.indices step 2) {
            val start = times[i].coerceIn(0, pcm.size / 16)
            val end = times[i + 1].coerceIn(start, pcm.size / 16)
            // FunASR sv_chunk uses 1.5 s windows, a 0.75 s stride and a right-aligned tail.
            var from = start
            while (from < end) {
                if (cancelled()) throw CancellationException()
                val until = minOf(from + 1500, end)
                val left = maxOf(start, until - 1500)
                val speech = FloatArray(24000)
                pcm.copyInto(speech, 0, left * 16, until * 16)
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(speech, 16000)
                    stream.inputFinished()
                    check(extractor.isReady(stream)) { "CAM++ 未能提取声音特征" }
                    val vector = extractor.compute(stream)
                    check(vector.isNotEmpty() && vector.all { it.isFinite() } && vector.any { it != 0f }) {
                        "CAM++ 返回了无效的声音特征"
                    }
                    embeddings.add(vector)
                } finally { stream.release() }
                chunks.add(SpeakerTurn(offset + left, offset + until, 0))
                if (until == end) break
                from += 750
            }
        }
        val labels = funSpeakerClusters(embeddings)
        val ids = profiles.assign(centers(embeddings, labels))
        return smoothTurns(chunks.mapIndexed { i, turn -> turn.copy(id = ids[labels[i]] ?: 0) })
    }

    override fun close() = extractor.release()
}

private fun centers(vectors: List<FloatArray>, labels: IntArray): Map<Int, FloatArray> =
    vectors.indices.groupBy { labels[it] }.mapValues { (_, members) ->
        FloatArray(vectors.first().size) { dim -> members.sumOf { vectors[it][dim].toDouble() }.div(members.size).toFloat() }
    }

private fun normalized(vector: FloatArray): DoubleArray {
    val norm = sqrt(vector.sumOf { it.toDouble() * it })
    return DoubleArray(vector.size) { if (norm > 0) vector[it] / norm else 0.0 }
}

private fun cosine(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

// Mobile implementation of FunASR ClusterBackend's short-recording and spectral paths.
// The 60 s input bound keeps each affinity matrix well below the upstream 2048-window switch.
internal fun funSpeakerClusters(vectors: List<FloatArray>): IntArray {
    val n = vectors.size
    val labels = IntArray(n)
    if (n < 20) return labels
    val unit = vectors.map(::normalized)
    val affinity = Array(n) { i -> DoubleArray(n) { j -> cosine(unit[i], unit[j]) } }
    val remove = ((1.0 - maxOf(0.022, 6.0 / n)) * n).toInt()
    for (row in affinity) row.indices.sortedBy { row[it] }.take(remove).forEach { row[it] = 0.0 }
    val laplacian = Array(n) { i ->
        DoubleArray(n) { j -> if (i == j) 0.0 else -(affinity[i][j] + affinity[j][i]) / 2 }
            .also { it[i] = it.sumOf(::abs) }
    }
    val eigen = EigenDecomposition(Array2DRowRealMatrix(laplacian, false))
    val order = (0 until n).sortedBy { eigen.getRealEigenvalue(it) }
    val k = (1..minOf(15, n - 1)).maxBy { eigen.getRealEigenvalue(order[it]) - eigen.getRealEigenvalue(order[it - 1]) }
    if (k == 1) return labels
    val axes = order.take(k).map { eigen.getEigenvector(it).toArray() }
    class Point(val index: Int) : Clusterable {
        private val coordinates = DoubleArray(k) { axes[it][index] }
        override fun getPoint() = coordinates
    }
    val clusterer = KMeansPlusPlusClusterer<Point>(k, 300, EuclideanDistance(), JDKRandomGenerator(0))
    clusterer.cluster(List(n) { Point(it) }).forEachIndexed { id, cluster -> cluster.points.forEach { labels[it.index] = id } }
    while (true) {
        val means = centers(vectors, labels).mapValues { normalized(it.value) }
        val pairs = means.keys.flatMap { a -> means.keys.filter { it > a }.map { b -> a to b } }
        val pair = pairs.maxByOrNull { (a, b) -> cosine(means.getValue(a), means.getValue(b)) } ?: break
        if (cosine(means.getValue(pair.first), means.getValue(pair.second)) < 0.78) break
        for (i in labels.indices) if (labels[i] == pair.second) labels[i] = pair.first
    }
    return labels
}

private fun merged(turns: List<SpeakerTurn>): MutableList<SpeakerTurn> {
    val result = mutableListOf<SpeakerTurn>()
    for (turn in turns) {
        val last = result.lastOrNull()
        if (last != null && last.id == turn.id && turn.start <= last.end) {
            result[result.lastIndex] = last.copy(end = maxOf(last.end, turn.end))
        } else result.add(turn)
    }
    return result
}

private fun smoothTurns(chunks: List<SpeakerTurn>): List<SpeakerTurn> {
    val turns = merged(chunks)
    for (i in 1 until turns.size) {
        val previous = turns[i - 1]
        val next = turns[i]
        if (previous.end > next.start) {
            val split = (previous.end + next.start) / 2
            turns[i - 1] = previous.copy(end = split)
            turns[i] = next.copy(start = split)
        }
    }
    if (turns.size < 2) return turns
    for ((i, t) in turns.withIndex()) if (t.end - t.start < 700) {
        val before = turns.getOrNull(i - 1)
        val after = turns.getOrNull(i + 1)
        val nearest = when {
            before == null -> after!!
            after == null -> before
            t.start - before.end <= after.start - t.end -> before
            else -> after
        }
        turns[i] = t.copy(id = nearest.id)
    }
    return merged(turns)
}
