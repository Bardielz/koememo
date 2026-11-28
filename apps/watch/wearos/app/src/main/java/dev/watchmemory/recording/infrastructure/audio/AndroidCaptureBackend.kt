package dev.watchmemory.recording.infrastructure.audio

import dev.watchmemory.recording.domain.*
import dev.watchmemory.recording.application.*
import android.media.*
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.*

class AndroidCaptureBackend(
    override val id: String = "android.media",
    private val matchesDevice: (WatchDevice) -> Boolean = { true },
    private val acceptsConfiguration: (RecordingConfiguration) -> Boolean = { true },
    private val openInput: (RecordingSource, Int) -> PcmInput = ::androidPcmInput,
    private val findEncoder: (MediaFormat) -> String? = { format ->
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
    }
) : CaptureBackend {
    override fun matches(device: WatchDevice) = matchesDevice(device)
    override fun supports(configuration: RecordingConfiguration) = acceptsConfiguration(configuration)

    override fun record(request: CaptureRequest, writer: RecordingWriter) {
        val (source, sampleRate, encoding, bitrate) = request.configuration
        val observer = request.observer
        val diagnostics = request.diagnostics
        val suppression = SessionNoiseSuppression()
        var mic: PcmInput? = null
        var codec: MediaCodec? = null
        var codecStarted = false
        var micStarted = false
        var primaryFailure: Throwable? = null
        try {
            mic = openInput(source, sampleRate)
            check(mic.sampleRate == sampleRate) { "Unexpected client sample rate: ${mic.sampleRate}" }
            // Read 128 ms at a time even when the codec accepts smaller input buffers.
            val pcm = ByteArray(sampleRate * 2 * 128 / 1000)
            val gate = if (request.configuration.quietGate) QuietGate(sampleRate) else null
            var micSilenced: Boolean? = null
            var inputPcm = pcm
            var direct = request.configuration.directPcm && gate == null
            val format = MediaFormat.createAudioFormat(AudioFormats.mime(encoding), sampleRate, 1).apply {
                if (encoding == RecordingEncoding.AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcm.size)
            }
            val encoderName = findEncoder(format)
                ?: error("设备不支持 ${encoding.name} / $sampleRate Hz / $bitrate bps")
            codec = MediaCodec.createByCodecName(encoderName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val ns = suppression.configure(mic.audioSessionId, source, request.configuration.noiseSuppression)
            observer.configured(ns)
            mic.start()
            micStarted = true
            observer.configured(mapOf("capture_backend" to id, "requested_sample_rate" to sampleRate,
                "client_sample_rate" to mic.sampleRate,
                "device_sample_rate" to (mic.deviceSampleRate ?: 0),
                "audio_record_buffer_frames" to mic.bufferSizeInFrames,
                "direct_pcm_requested" to request.configuration.directPcm, "reuse_ogg" to request.configuration.reuseOgg,
                "pcm_batch_bytes" to pcm.size, "encoder" to codec.name, "encoding" to encoding.name, "target_bitrate" to bitrate,
                "encoder_software_only" to codec.codecInfo.isSoftwareOnly,
                "encoder_hardware_accelerated" to codec.codecInfo.isHardwareAccelerated,
                "encoder_vendor" to codec.codecInfo.isVendor))
            if (request.configuration.directPcm && gate != null) {
                observer.configured(mapOf("direct_pcm_active" to false, "direct_pcm_fallback" to true,
                    "direct_pcm_fallback_reason" to "quiet_gate"))
            }
            val encoded = ByteArray(8191)
            var pending = 0
            var offset = 0
            var lastLevel = 0L
            fun reportLevel(length: Int, directBuffer: ByteBuffer? = null) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel < 500) return
                diagnostics.measure(RecordingStage.LEVEL) {
                    // Sample only at the meter cadence; bulk read avoids per-sample direct-buffer access.
                    directBuffer?.get(pcm, 0, length)
                    var sum = 0.0
                    for (i in 0 until length step 2) {
                        val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                        sum += value * value
                    }
                    observer.level(20 * log10(max(1.0, sqrt(sum / (length / 2))) / 32768.0))
                }
                lastLevel = now
            }
            var samples = 0L
            var lastCheck = 0L
            var hasEncodedAudio = false
            var dirty = false
            var lastSync = SystemClock.elapsedRealtime()
            var lastDiagnostics = lastSync
            var nextAnchorAt = 0L
            var inputEnded = false
            var outputEnded = false
            var deadline = Long.MAX_VALUE
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (request.stopping() && deadline == Long.MAX_VALUE) deadline = SystemClock.elapsedRealtime() + 5000
                check(SystemClock.elapsedRealtime() <= deadline) { "Encoder drain timed out" }
                if (!inputEnded) {
                    if (!direct && offset == pending && !request.stopping()) {
                        pending = diagnostics.measure(RecordingStage.CAPTURE_READ) { mic.read(pcm) }
                        offset = 0
                        if (pending <= 0 || pending % 2 != 0) throw IOException("Microphone read error: $pending")
                        reportLevel(pending)
                        if (gate != null) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastCheck >= 5000 || lastCheck == 0L) {
                                micSilenced = mic.silenced
                                micSilenced?.let(observer::silenced)
                                lastCheck = now
                            }
                            val wasPaused = gate.paused
                            // An encoder which never produces its first packet must still time out.
                            val output = gate.accept(pcm, pending, hasEncodedAudio && micSilenced == false)
                            if (gate.paused) observer.quietProgress()
                            if (wasPaused != gate.paused) {
                                observer.quietTransition(if (gate.paused) "pause" else "resume",
                                    gate.capturedSamples, gate.heldSamples, gate.replaySamples)
                            }
                            inputPcm = output ?: pcm
                            pending = when {
                                output == null -> 0
                                output === pcm -> pending
                                else -> output.size
                            }
                        }
                    }
                    val slot = if (direct || offset < pending || request.stopping()) {
                        diagnostics.measure(RecordingStage.ENCODER_INPUT_WAIT) { codec.dequeueInputBuffer(10000) }
                    } else -1
                    if (slot >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(slot))
                        input.clear()
                        val capacity = min(pcm.size, input.remaining()) and -2
                        check(capacity > 0) { "Encoder input buffer too small" }
                        val directCount = if (direct && !request.stopping() && input.isDirect)
                            diagnostics.measure(RecordingStage.CAPTURE_READ) { mic.readDirect(input, capacity) }
                        else null
                        if (directCount != null) {
                            if (directCount !in 1..capacity || directCount % 2 != 0) throw IOException("Microphone read error: $directCount")
                            if (samples == 0L) observer.configured(mapOf("direct_pcm_active" to true,
                                "codec_slot_bytes" to input.capacity(), "first_read_bytes" to directCount))
                            reportLevel(directCount, input)
                        } else if (direct && !request.stopping()) {
                            direct = false
                            observer.configured(mapOf("direct_pcm_fallback" to true))
                            pending = diagnostics.measure(RecordingStage.CAPTURE_READ) { mic.read(pcm) }
                            if (pending <= 0 || pending % 2 != 0) throw IOException("Microphone read error: $pending")
                            offset = 0
                            reportLevel(pending)
                        }
                        val count = directCount ?: min(pending - offset, capacity)
                        diagnostics.measure(RecordingStage.ENCODER_INPUT) {
                            if (directCount == null && count > 0) input.put(inputPcm, offset, count)
                            codec.queueInputBuffer(slot, 0, count, samples * 1_000_000 / sampleRate,
                                if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        }
                        samples += count / 2
                        if (directCount == null) offset += count
                        inputEnded = count == 0
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
                            hasEncodedAudio = true
                            dirty = true
                            produced++
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(slot, false) }
                    if (outputEnded) break
                }
                if (produced > 0) observer.frames(produced)
                val now = SystemClock.elapsedRealtime()
                if (now >= nextAnchorAt) {
                    val anchor = try { mic.captureTimestamp() } catch (_: RuntimeException) { null }
                    if (anchor != null) observer.configured(mapOf("audio_boottime_ns" to anchor.nanoTime,
                        "audio_frame_position" to anchor.framePosition, "sample_rate" to sampleRate))
                    nextAnchorAt = now + if (anchor == null) 5000 else 60000
                }
                if (gate == null && now - lastCheck >= 5000) {
                    mic.silenced?.let(observer::silenced)
                    lastCheck = now
                }
                if (now - lastDiagnostics >= 60000) { request.reportDiagnostics(); lastDiagnostics = now }
                if (dirty && now - lastSync >= 30000) { writer.sync(); dirty = false; lastSync = now }
            }
            observer.configured(mapOf("capture_end_samples" to (gate?.capturedSamples ?: samples)))
            if (gate?.paused == true) observer.quietTransition("stop", gate.capturedSamples, gate.heldSamples, 0)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(stage: String, action: () -> Unit) {
                try { recordingStage(stage, action) }
                catch (error: Throwable) {
                    val previous = primaryFailure ?: cleanupFailure
                    if (previous != null) previous.addSuppressed(error) else cleanupFailure = error
                }
            }
            cleanup("MIC_STOP") { if (micStarted) mic?.stop() }
            suppression.release()
            cleanup("MIC_RELEASE") { mic?.release() }
            cleanup("CODEC_STOP") { if (codecStarted) codec?.stop() }
            cleanup("CODEC_RELEASE") { codec?.release() }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
