package dev.watchmemory.recording.infrastructure.audio

import dev.watchmemory.recording.infrastructure.opus.OpusPackets
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Normalize Opus packet timestamps so repeated extractor timestamps cannot reapply pre-skip. */
class OpusPlayback(private val file: File, private val completed: (String?) -> Unit) {
    @Volatile private var running = true
    fun stop() { running = false }
    fun start() {
        Thread({
            var failure: String? = null
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var track: AudioTrack? = null
            try {
                extractor.setDataSource(file.absolutePath); extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == "audio/opus")
                decoder = MediaCodec.createDecoderByType("audio/opus")
                decoder.configure(format, null, null, 0); decoder.start()
                var inputEnded = false; var outputEnded = false; var clockUs = 0L
                var samplesWritten = 0L
                var lastProgress = SystemClock.elapsedRealtime()
                val info = MediaCodec.BufferInfo()
                val packet = ByteArray(65536)
                while (running && !outputEnded) {
                    check(SystemClock.elapsedRealtime() - lastProgress < 10000) { "Opus 试听解码超时" }
                    if (!inputEnded) {
                        val slot = decoder.dequeueInputBuffer(10000)
                        if (slot >= 0) {
                            val input = checkNotNull(decoder.getInputBuffer(slot)); input.clear()
                            val size = extractor.readSampleData(input, 0)
                            val pts = clockUs
                            if (size >= 0) {
                                check(size in 1..packet.size)
                                input.position(0); input.get(packet, 0, size); input.position(0)
                                clockUs += OpusPackets.samples(packet, size) * 1_000_000L / 48000
                                extractor.advance()
                            }
                            inputEnded = size < 0
                            decoder.queueInputBuffer(slot, 0, maxOf(size, 0), pts,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    val slot = decoder.dequeueOutputBuffer(info, 10000)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(track == null) { "Opus 试听格式意外变化" }
                        val output = decoder.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        check(output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        check(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0)
                        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(maxOf(minimum * 2, rate * 2 / 5)).build()
                        track.play()
                    } else if (slot >= 0) {
                        try {
                            val output = checkNotNull(decoder.getOutputBuffer(slot))
                            output.position(info.offset); output.limit(info.offset + info.size)
                            while (running && output.hasRemaining()) {
                                val written = checkNotNull(track).write(output, output.remaining(), AudioTrack.WRITE_BLOCKING)
                                check(written > 0) { "Opus 试听音频输出失败" }
                                samplesWritten += written / 2
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = SystemClock.elapsedRealtime()
                        } finally { decoder.releaseOutputBuffer(slot, false) }
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (running && track != null && (track.playbackHeadPosition.toLong() and 0xffffffffL) < samplesWritten && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(20)
                }
            } catch (e: Exception) { failure = e.message ?: "Opus 试听失败" }
            finally { try { track?.release() } finally { try { decoder?.release() } finally { extractor.release() } } }
            Handler(Looper.getMainLooper()).post { if (running) completed(failure) }
        }, "opus-playback").start()
    }
}
