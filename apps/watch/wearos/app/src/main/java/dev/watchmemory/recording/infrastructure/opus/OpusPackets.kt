package dev.watchmemory.recording.infrastructure.opus

/** RFC 6716 section 3: packet duration expressed on the Opus 48 kHz clock. */
object OpusPackets {
    fun samples(bytes: ByteArray, length: Int = bytes.size): Int {
        require(length in 1..bytes.size)
        val toc = bytes[0].toInt() and 255
        val perFrame = when {
            toc and 0x80 != 0 -> 120 shl ((toc shr 3) and 3)
            toc and 0x60 == 0x60 -> if (toc and 8 != 0) 960 else 480
            (toc shr 3) and 3 == 3 -> 2880
            else -> 480 shl ((toc shr 3) and 3)
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> { require(length >= 2); bytes[1].toInt() and 63 }
        }
        require(count > 0 && perFrame * count <= 5760) { "Invalid Opus packet duration" }
        return perFrame * count
    }
}
