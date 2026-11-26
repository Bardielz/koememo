package dev.koememo.phone

import android.content.Context
import android.media.MediaPlayer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/** Playback controls for the selected local recording. All callbacks run on the UI thread. */
internal class RecordingPlayer(context: Context) : LinearLayout(context) {
    private val title = TextView(context)
    private val time = TextView(context)
    private val seek = SeekBar(context).apply { contentDescription = "播放进度" }
    private val toggle = Button(context)
    private var player: MediaPlayer? = null
    private var dragging = false
    private var seeking = false
    private var duration = 0
    private val update = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!dragging && !seeking) showPosition(current.currentPosition)
            if (current.isPlaying && isShown) postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        addView(title)
        addView(seek)
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(time, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        })
        toggle.setOnClickListener {
            val current = player ?: return@setOnClickListener
            if (current.isPlaying) current.pause() else current.start()
            refreshControls(current)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                seekTo(bar.progress)
            }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                time.text = timestamp(value)
                if (!dragging) seekTo(value) // Keyboard and accessibility actions.
            }
        })
    }

    private fun seekTo(ms: Int) {
        val current = player ?: return
        seeking = true
        current.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun play(file: File, startMs: Int = 0) {
        release()
        title.text = file.name
        time.text = "正在读取录音…"
        seek.isEnabled = false
        seek.progress = 0
        toggle.text = "播放"
        toggle.isEnabled = false
        visibility = View.VISIBLE
        val current = MediaPlayer()
        player = current
        current.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            duration = it.duration
            seek.max = duration.coerceAtLeast(1)
            seek.isEnabled = duration > 0
            toggle.isEnabled = true
            if (startMs > 0) seekTo(startMs.coerceAtMost(duration))
            it.start()
            refreshControls(it)
        }
        current.setOnSeekCompleteListener {
            if (player === it) {
                seeking = false
                if (!dragging) showPosition(it.currentPosition)
            }
        }
        current.setOnCompletionListener {
            if (player === it) {
                removeCallbacks(update)
                showPosition(if (duration > 0) duration else it.currentPosition)
                toggle.text = "重播"
            }
        }
        current.setOnErrorListener { failed, _, _ ->
            if (player === failed) showError()
            true
        }
        runCatching {
            current.setDataSource(file.path)
            current.prepareAsync()
        }.onFailure { showError() }
    }

    private fun refreshControls(current: MediaPlayer) {
        toggle.text = if (current.isPlaying) "暂停播放" else "继续播放"
        removeCallbacks(update)
        update.run()
    }

    fun resumeUpdates() {
        if (toggle.isEnabled && player?.isPlaying == true) {
            removeCallbacks(update)
            post(update)
        }
    }

    fun suspendUpdates() { removeCallbacks(update) }

    private fun showPosition(ms: Int) {
        seek.progress = ms
        time.text = timestamp(ms)
    }

    private fun timestamp(ms: Int): String {
        fun format(value: Int): String {
            val seconds = value.coerceAtLeast(0) / 1000
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
        return format(ms) + " / " + if (duration > 0) format(duration) else "--:--"
    }

    private fun showError() {
        release()
        time.text = "无法播放这段录音"
        toggle.isEnabled = false
        seek.isEnabled = false
    }

    fun release() {
        removeCallbacks(update)
        val old = player
        player = null
        old?.release()
        seeking = false
        dragging = false
        duration = 0
    }
}
