package dev.watchmemory.recording.infrastructure.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Silero v5.1 streaming CPU inference. All supported capture rates divide exactly to 8 kHz. */
internal class VoiceProbability private constructor(
    private val session: OrtSession, rate: Int, private val env: OrtEnvironment
) : AutoCloseable {
    private val ratio = rate / 8000
    private val input = FloatArray(32 + 256)
    private val state = FloatArray(256)
    private val sampleRate = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(8000)), longArrayOf())
    private var count = 32
    private var sum = 0
    private var group = 0
    private var last = 1f

    fun score(pcm: ByteArray, size: Int): Float {
        var peak = -1f
        for (i in 0 until size step 2) {
            sum += (pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)
            if (++group < ratio) continue
            input[count++] = sum / (32768f * ratio)
            group = 0
            sum = 0
            if (count != input.size) continue
            last = infer()
            peak = maxOf(last, peak)
            input.copyInto(input, 0, input.size - 32, input.size)
            count = 32
        }
        return if (peak >= 0f) peak else last
    }

    private fun infer(): Float = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 288)).use { audio ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { memory ->
            session.run(mapOf("input" to audio, "state" to memory, "sr" to sampleRate)).use { result ->
                (result[1] as OnnxTensor).floatBuffer.get(state)
                (result[0] as OnnxTensor).floatBuffer.get().also { check(it in 0f..1f) }
            }
        }
    }

    override fun close() { sampleRate.close(); session.close() }

    companion object {
        fun open(assets: AssetManager, rate: Int): VoiceProbability {
            require(rate in setOf(16000, 24000, 48000))
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                assets.open("silero/vad-v5.1.onnx").use { env.createSession(it.readBytes(), options) }
            }
            return try { VoiceProbability(session, rate, env) } catch (e: Exception) { session.close(); throw e }
        }
    }
}
