package dev.koememo.phone

import android.content.Context
import android.media.MediaPlayer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.koememo.sync.ContextWindow
import dev.koememo.sync.PlaybackSpan
import dev.koememo.sync.sessionTimeline
import dev.koememo.sync.sha256
import java.io.File

/** One set of transport controls for a recording or a verified excerpt. */
internal class RecordingPlayer(context: Context) : LinearLayout(context) {
    private val title = TextView(context)
    private val time = TextView(context)
    private val contextLabel = TextView(context)
    private val seek = SeekBar(context).apply { contentDescription = "播放进度" }
    private val toggle = Button(context)
    private var queue = SessionPlayback(emptyList())
    private var index = 0
    private var offset = 0
    private var player: MediaPlayer? = null
    private var prepared = false
    private var ended = false
    private var dragging = false
    private var seeking = false
    private var localDuration = 0
    private var contextSpan: PlaybackSpan? = null
    private var shownWindow: ContextWindow? = null
    private var session = false
    private val total get() = queue.totalMs ?: localDuration.toLong()
    val currentFile get() = queue.tracks.getOrNull(index)?.file
    val currentOffset get() = if (prepared && !seeking) player?.currentPosition ?: offset else offset
    private val update = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!prepared) return
            if (!dragging && !seeking) render(current.currentPosition)
            if (current.isPlaying && isShown) postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        addView(title); addView(contextLabel); addView(seek)
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(time, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        })
        toggle.setOnClickListener {
            val current = player
            when {
                ended -> open(0, 0, true)
                current == null -> open(index, offset, true)
                prepared -> {
                    if (current.isPlaying) current.pause() else current.start()
                    refreshControls(current)
                }
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                move(fromProgress(bar.progress))
            }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ms = fromProgress(value)
                time.text = timestamp(ms)
                if (!dragging) move(ms)
            }
        })
    }

    fun bind(playlist: SessionPlayback, resumeFile: File? = null, resumeMs: Int = 0) {
        release()
        queue = playlist
        if (queue.tracks.isEmpty()) { visibility = View.GONE; return }
        session = true
        index = queue.tracks.indexOfFirst { it.file == resumeFile }.coerceAtLeast(0)
        offset = if (currentFile == resumeFile) resumeMs.coerceAtLeast(0) else 0
        title.text = "本次录音"
        toggle.text = if (offset > 0 || index > 0) "继续播放" else "播放这次录音"
        toggle.isEnabled = true
        visibility = View.VISIBLE
        configureSeek()
        render(offset)
    }

    fun play(file: File, startMs: Int = 0, verifiedSpan: PlaybackSpan? = null, label: String = file.name) {
        release()
        session = false
        queue = SessionPlayback(listOf(AudioTrack(file, null, verifiedSpan)))
        title.text = label
        visibility = View.VISIBLE
        open(0, startMs, true)
    }

    fun playFrom(file: File, startMs: Int): Boolean {
        val target = queue.tracks.indexOfFirst { it.file == file }
        if (!session || target < 0) return false
        open(target, startMs, true)
        return true
    }

    private fun open(target: Int, startMs: Int, autoplay: Boolean) {
        val track = queue.tracks[target]
        closeDecoder()
        ended = false
        index = target
        offset = startMs
        toggle.isEnabled = false
        seek.isEnabled = false
        time.text = "正在读取录音…"
        val current = MediaPlayer()
        player = current
        track.span?.let { setContext(it, offset) }
        if (!session && track.span == null) Thread {
            val span = runCatching {
                sessionTimeline(track.file).firstNotNullOfOrNull { run ->
                    run.spans.find { it.file == track.file.name && it.windows.isNotEmpty() }
                }?.takeIf { track.file.sha256() == it.hash }
            }.getOrNull()
            post { if (player === current && span != null) setContext(span, currentOffset) }
        }.start()
        current.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true
            localDuration = it.duration.coerceAtLeast(0)
            configureSeek()
            toggle.isEnabled = true
            if (startMs > 0) seekLocal(startMs)
            if (autoplay) it.start()
            render(offset)
            refreshControls(it)
        }
        current.setOnSeekCompleteListener {
            if (player === it) {
                seeking = false
                if (!dragging) render(it.currentPosition)
            }
        }
        current.setOnCompletionListener {
            if (player === it) {
                if (index < queue.tracks.lastIndex) open(index + 1, 0, true)
                else {
                    ended = true
                    removeCallbacks(update)
                    render(localDuration)
                    toggle.text = "重播"
                }
            }
        }
        current.setOnErrorListener { failed, _, _ -> if (player === failed) showError(); true }
        runCatching { current.setDataSource(track.file.path); current.prepareAsync() }.onFailure { showError() }
    }

    private fun fromProgress(value: Int) = if (total > Int.MAX_VALUE)
        (value.toDouble() * total / seek.max).toLong() else value.toLong()

    private fun configureSeek() {
        seek.max = total.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        seek.isEnabled = total > 0
    }

    private fun move(ms: Long) {
        if (queue.tracks.isEmpty()) return
        val pos = queue.locate(ms) ?: AudioPosition(index, ms.toInt())
        val playing = prepared && player?.isPlaying == true
        ended = false
        if (pos.index != index || !prepared) open(pos.index, pos.offsetMs, playing)
        else { seekLocal(pos.offsetMs); refreshControls(checkNotNull(player)) }
    }

    private fun seekLocal(ms: Int) {
        offset = ms.coerceIn(0, localDuration)
        seeking = true
        player?.seekTo(offset.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun refreshControls(current: MediaPlayer) {
        toggle.text = if (current.isPlaying) "暂停播放" else "继续播放"
        removeCallbacks(update)
        update.run()
    }

    fun resumeUpdates() {
        if (prepared && player?.isPlaying == true) { removeCallbacks(update); post(update) }
    }
    fun suspendUpdates() { removeCallbacks(update) }
    fun pauseForNavigation() {
        val current = player ?: return
        if (!prepared) { val file = currentFile; if (session) bind(queue, file, offset) else release(); return }
        if (current.isPlaying) { current.pause(); refreshControls(current) }
    }

    private fun render(ms: Int) {
        offset = ms
        val elapsed = if (queue.totalMs != null) queue.elapsed(index, ms).coerceAtMost(total) else ms.toLong()
        seek.progress = if (total > Int.MAX_VALUE) (elapsed.toDouble() / total * seek.max).toInt() else elapsed.toInt()
        time.text = timestamp(elapsed)
        val span = contextSpan ?: return
        val window = span.contextAt(ms)
        if (shownWindow != window) {
            shownWindow = window
            contextLabel.text = window?.description() ?: "当前位置暂无传感器样本"
        }
    }
    private fun setContext(span: PlaybackSpan, ms: Int) {
        if (span.windows.isEmpty()) return
        contextSpan = span
        contextLabel.visibility = View.VISIBLE
        shownWindow = span.contextAt(ms)
        contextLabel.text = shownWindow?.description() ?: "当前位置暂无传感器样本"
    }
    private fun timestamp(ms: Long): String {
        val scope = if (session && queue.totalMs == null) "当前音频　" else ""
        return scope + duration(ms / 1000) + " / " + if (total > 0) duration(total / 1000) else "--:--"
    }

    private fun showError() {
        closeDecoder()
        time.text = "当前位置无法播放，可重试"
        toggle.text = "重试播放"
        toggle.isEnabled = true
        configureSeek()
    }
    private fun closeDecoder() {
        removeCallbacks(update)
        val old = player
        player = null
        old?.release()
        prepared = false
        seeking = false
        dragging = false
        contextSpan = null
        shownWindow = null
        contextLabel.visibility = View.GONE
        localDuration = 0
    }
    fun release() {
        closeDecoder()
        queue = SessionPlayback(emptyList())
        index = 0; offset = 0; ended = false
        visibility = View.GONE
    }
}
