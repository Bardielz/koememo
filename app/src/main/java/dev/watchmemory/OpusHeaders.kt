package dev.watchmemory

import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpusHeaders {
    fun identification(csd: ByteArray): ByteArray {
        fun starts(bytes: ByteArray, value: String) = bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == value
        var header: ByteArray? = null
        var delayNs: Long? = null
        if (starts(csd, "OpusHead")) header = csd.copyOf()
        else {
            val input = ByteBuffer.wrap(csd).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) {
                require(input.remaining() >= 16) { "Truncated Android Opus CSD" }
                val tag = ByteArray(8).also { input.get(it) }.toString(Charsets.US_ASCII)
                val size = input.long
                require(size >= 0 && size <= input.remaining()) { "Invalid Opus CSD size" }
                val bytes = ByteArray(size.toInt()).also { input.get(it) }
                if (tag == "AOPUSHDR") header = bytes
                if (tag == "AOPUSDLY") {
                    require(bytes.size == 8)
                    delayNs = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
                }
            }
        }
        val result = checkNotNull(header) { "OpusHead missing" }
        require(result.size == 19 && starts(result, "OpusHead") && result[8].toInt() == 1 && result[9].toInt() == 1 && result[18].toInt() == 0) {
            "Unsupported mono Opus header"
        }
        // Android's wrapped encoder header can express pre-skip at the input rate.
        // The accompanying nanosecond delay is authoritative; Ogg uses 48 kHz units.
        delayNs?.let {
            require(it in 0..1_000_000_000L)
            val samples = (it * 48000 + 500_000_000L) / 1_000_000_000L
            result[10] = samples.toByte(); result[11] = (samples.toInt() shr 8).toByte()
        }
        return result
    }
}
