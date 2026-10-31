package dev.watchmemory.recording.infrastructure.audio

import dev.watchmemory.recording.domain.*
import dev.watchmemory.recording.application.*
import android.annotation.SuppressLint
import android.media.*
import android.os.SystemClock
import java.io.IOException
import kotlin.math.*

class CaptureEngine(private val observer: Observer, private val source: RecordingSource, private val sampleRate: Int, private val encoding: RecordingEncoding, private val bitrate: Int,
    private val diagnostics: RecordingDiagnostics, private val reportDiagnostics: () -> Unit) {
    interface Observer {
        fun configured(fields: Map<String, Any>)
        fun level(db: Double)
        fun frames(count: Int)
        fun silenced(value: Boolean)
    }
    @Volatile private var running = true
    fun requestStop() { running = false }

    @SuppressLint("MissingPermission") // Permission checked by visible activity and service.
    fun record(writer: RecordingWriter) {
        var mic: AudioRecord? = null
        var codec: MediaCodec? = null
        var codecStarted = false
        var micStarted = false
        try {
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Microphone does not support $sampleRate Hz" }
            mic = AudioRecord(AudioFormats.sourceId(source), sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minimum * 2, sampleRate * 2))
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            check(mic.sampleRate == sampleRate) { "Unexpected client sample rate: ${mic.sampleRate}" }
            // Read 128 ms at a time even when the codec accepts smaller input buffers.
            val pcm = ByteArray(sampleRate * 2 * 128 / 1000)
            val format = MediaFormat.createAudioFormat(AudioFormats.mime(encoding), sampleRate, 1).apply {
                if (encoding == RecordingEncoding.AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcm.size)
            }
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
                ?: error("设备不支持 ${encoding.name} / $sampleRate Hz / $bitrate bps")
            codec = MediaCodec.createByCodecName(encoderName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            mic.startRecording()
            micStarted = true
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            observer.configured(mapOf("requested_sample_rate" to sampleRate,
                "client_sample_rate" to mic.sampleRate,
                "device_sample_rate" to (mic.activeRecordingConfiguration?.format?.sampleRate ?: 0),
                "audio_record_buffer_frames" to mic.bufferSizeInFrames,
                "pcm_batch_bytes" to pcm.size, "encoder" to codec.name, "encoding" to encoding.name, "target_bitrate" to bitrate,
                "encoder_software_only" to codec.codecInfo.isSoftwareOnly,
                "encoder_hardware_accelerated" to codec.codecInfo.isHardwareAccelerated,
                "encoder_vendor" to codec.codecInfo.isVendor))
            val encoded = ByteArray(8191)
            var pending = 0
            var offset = 0
            var lastLevel = 0L
            var samples = 0L
            var lastCheck = 0L
            var lastSync = SystemClock.elapsedRealtime()
            var lastDiagnostics = lastSync
            var inputEnded = false
            var outputEnded = false
            var deadline = Long.MAX_VALUE
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!running && deadline == Long.MAX_VALUE) deadline = SystemClock.elapsedRealtime() + 5000
                check(SystemClock.elapsedRealtime() <= deadline) { "Encoder drain timed out" }
                if (!inputEnded) {
                    if (offset == pending && running) {
                        pending = diagnostics.measure(RecordingStage.CAPTURE_READ) { mic.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) }
                        offset = 0
                        if (pending <= 0 || pending % 2 != 0) throw IOException("Microphone read error: $pending")
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLevel >= 500) {
                            diagnostics.measure(RecordingStage.LEVEL) {
                                var sum = 0.0
                                for (i in 0 until pending step 2) {
                                    val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                                    sum += value * value
                                }
                                observer.level(20 * log10(max(1.0, sqrt(sum / (pending / 2))) / 32768.0))
                            }
                            lastLevel = now
                        }
                    }
                    diagnostics.measure(RecordingStage.ENCODER_INPUT) {
                        val slot = codec.dequeueInputBuffer(10000)
                        if (slot >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(slot))
                            input.clear()
                            val count = min(pending - offset, input.remaining()) and -2
                            check(count > 0 || (!running && offset == pending)) { "Encoder input buffer too small" }
                            if (count > 0) input.put(pcm, offset, count)
                            codec.queueInputBuffer(slot, 0, count, samples * 1_000_000 / sampleRate,
                                if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            samples += count / 2
                            offset += count
                            inputEnded = count == 0
                        }
                    }
                }
                var produced = 0
                while (true) {
                    val slot = diagnostics.measure(RecordingStage.ENCODER_OUTPUT_WAIT) { codec.dequeueOutputBuffer(info, if (inputEnded) 10000 else 0) }
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actual = codec.outputFormat
                        val encodedRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        check((encodedRate == sampleRate || (encoding == RecordingEncoding.OPUS && encodedRate == 48000)) &&
                            actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "Unexpected encoder output format: $actual" }
                        writer.configure(AudioFormats.encoded(actual))
                        observer.configured(mapOf("encoded_sample_rate" to encodedRate, "encoded_channels" to 1))
                        continue
                    }
                    if (slot < 0) break
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(codec.getOutputBuffer(slot))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            check(info.size <= encoded.size) { "Encoded packet too large" }
                            diagnostics.measure(RecordingStage.OUTPUT_COPY) { output.get(encoded, 0, info.size) }
                            writer.write(encoded, info.presentationTimeUs, info.size)
                            produced++
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(slot, false) }
                    if (outputEnded) break
                }
                if (produced > 0) observer.frames(produced)
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck >= 5000) {
                    mic.activeRecordingConfiguration?.let { observer.silenced(it.isClientSilenced) }
                    lastCheck = now
                }
                if (now - lastDiagnostics >= 60000) { reportDiagnostics(); lastDiagnostics = now }
                if (now - lastSync >= 30000) { writer.sync(); lastSync = now }
            }
        } finally {
            try {
                try { if (micStarted) mic?.stop() } finally { mic?.release() }
            } finally {
                try { if (codecStarted) codec?.stop() } finally { codec?.release() }
            }
        }
    }
}
