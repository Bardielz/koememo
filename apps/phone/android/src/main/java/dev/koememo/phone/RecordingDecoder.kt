package dev.koememo.phone

import android.media.*
import android.os.SystemClock
import dev.watchmemory.recording.infrastructure.opus.OpusPackets
import java.io.File
import java.nio.ByteOrder

/** Decode one KoeMemo segment with bounded memory; timestamps use the saved media timeline. */
internal object RecordingDecoder {
    fun read(file: File, consume: (FloatArray, Int, Int) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val toc = ByteArray(2)
            var opusUs = 0L
            var inputEnded = false
            var window = FloatArray(rate * 15)
            var count = 0
            var lastProgress = SystemClock.elapsedRealtime()
            while (!Thread.currentThread().isInterrupted) {
                check(SystemClock.elapsedRealtime() - lastProgress < 30000) { "音频解码超时" }
                if (!inputEnded) {
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(slot))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        inputEnded = size < 0
                        val pts = if (mime == "audio/opus") opusUs else extractor.sampleTime.coerceAtLeast(0)
                        if (!inputEnded) {
                            if (mime == "audio/opus") {
                                val n = minOf(2, size)
                                for (i in 0 until n) toc[i] = input.get(i)
                                opusUs += OpusPackets.samples(toc, n) * 1000000L / 48000
                            }
                            extractor.advance()
                        }
                        codec.queueInputBuffer(slot, 0, size.coerceAtLeast(0), pts,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, 10000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = codec.outputFormat
                    check(decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "只支持单声道录音" }
                    check(decoded.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT)
                    rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    check(count == 0 && rate in 8000..96000)
                    window = FloatArray(rate * 15)
                } else if (slot >= 0) {
                    try {
                        if (info.size > 0) {
                            val data = checkNotNull(codec.getOutputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                            data.position(info.offset); data.limit(info.offset + info.size)
                            val shorts = data.asShortBuffer()
                            while (shorts.hasRemaining()) {
                                window[count++] = shorts.get() / 32768f
                                if (count == window.size) {
                                    consume(window, rate, count)
                                    count = 0
                                }
                            }
                        }
                    } finally { codec.releaseOutputBuffer(slot, false) }
                    lastProgress = SystemClock.elapsedRealtime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (count > 0) consume(window.copyOf(count), rate, count)
                        return
                    }
                }
            }
            throw InterruptedException()
        } finally { try { decoder?.release() } finally { extractor.release() } }
    }
}
