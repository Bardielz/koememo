package dev.watchmemory.recording.infrastructure.audio

import android.media.MediaPlayer
import dev.watchmemory.recording.application.RecordingPlayback
import java.io.File

class AndroidRecordingPlayback : RecordingPlayback {
    private var aac: MediaPlayer? = null
    private var opus: OpusPlayback? = null
    override val isPlaying get() = aac != null || opus != null
    override fun play(path: String, completed: (String?) -> Unit) {
        stop()
        val file = File(path)
        if (file.extension == "ogg") {
            opus = OpusPlayback(file) { error -> stop(); completed(error) }
            opus!!.start(); return
        }
        try {
            aac = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop(); completed(null) }
                setOnErrorListener { _, _, _ -> stop(); completed("片段无法播放，请导出检查"); true }
                prepare(); start()
            }
        } catch (e: Exception) { stop(); completed(e.message ?: "无法播放片段") }
    }
    override fun stop() { opus?.stop(); opus = null; aac?.release(); aac = null }
}
