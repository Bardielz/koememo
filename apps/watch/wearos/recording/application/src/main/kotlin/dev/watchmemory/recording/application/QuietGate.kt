package dev.watchmemory.recording.application

import kotlin.math.min

/** Mono little-endian PCM16. All durations use input samples, independent of read scheduling. */
class QuietGate(private val rate: Int) {
    private val history = ByteArray(rate * 2)
    private var cursor = 0
    private var retained = 0
    private var quietSamples = 0L
    var paused = false
        private set
    var capturedSamples = 0L
        private set
    var heldSamples = 0L
        private set
    var replaySamples = 0
        private set

    /** Pass the input through, return null while gated, or return replay plus the current block. */
    fun accept(pcm: ByteArray, size: Int, micAvailable: Boolean, voiceProbability: Float? = null): ByteArray? {
        require(size in 2..pcm.size && size % 2 == 0)
        capturedSamples += size / 2
        // Short windows preserve transients which would disappear into a 128 ms average.
        val audible = !micAvailable || if (voiceProbability == null) {
            aboveThreshold(pcm, size, if (paused) 104 else 59)
        } else {
            voiceProbability >= (if (paused) 0.60f else 0.35f)
        }
        if (!paused) {
            quietSamples = if (audible) 0 else quietSamples + size / 2
            if (quietSamples < 10L * rate) return pcm
            paused = true
            heldSamples = 0
            retained = 0
            cursor = 0
            replaySamples = 0
        } else if (audible) {
            paused = false
            quietSamples = 0
            replaySamples = retained / 2
            val output = ByteArray(retained + size)
            val start = (cursor - retained + history.size) % history.size
            val first = min(retained, history.size - start)
            history.copyInto(output, 0, start, start + first)
            history.copyInto(output, first, 0, retained - first)
            pcm.copyInto(output, retained, 0, size)
            return output
        }
        var offset = 0
        while (offset < size) {
            val count = min(size - offset, history.size - cursor)
            pcm.copyInto(history, cursor, offset, offset + count)
            cursor = (cursor + count) % history.size
            offset += count
        }
        retained = min(history.size, retained + size)
        heldSamples += size / 2
        return null
    }

    private fun aboveThreshold(pcm: ByteArray, size: Int, amplitude: Int): Boolean {
        var offset = 0
        while (offset < size) {
            val end = min(size, offset + 2 * (rate / 100))
            var energy = 0L
            val samples = (end - offset) / 2
            while (offset < end) {
                val value = (pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)
                energy += value * value
                offset += 2
            }
            if (energy >= samples.toLong() * amplitude * amplitude) return true
        }
        return false
    }
}
