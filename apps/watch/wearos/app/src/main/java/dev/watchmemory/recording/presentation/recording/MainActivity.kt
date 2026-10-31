package dev.watchmemory.recording.presentation.recording

import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.SettingsActivity
import dev.watchmemory.recording.application.RecordingCommand
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

open class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var timer: TextView
    private lateinit var detail: TextView
    private lateinit var main: Button
    private lateinit var mark: Button
    private lateinit var finish: Button
    private lateinit var playback: Button
    private lateinit var settingsButton: Button
    private val graph by lazy { AppGraph.get(this) }
    private var lastStats = 0L
    private var deviceStats = ""
    private var lastScan = 0L
    private var fileSummary = ""
    private var latestFile: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            if (interactive) render()
            handler.postDelayed(this, if (interactive) 1000 else 5000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!graph.state.busy) graph.sessions.recoverAfterProcessDeath()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(34), dp(28), dp(34), dp(36))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column) }
        setContentView(scroll)
        fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        label("KoeMemo", 15f, Color.rgb(126, 226, 198))
        timer = label("00:00:00", 27f, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        state = label("准备记录", 13f, Color.LTGRAY)
        fun button(text: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
            this.text = text; textSize = 14f; isAllCaps = false; minHeight = dp(44)
            setTextColor(if (primary) Color.rgb(8, 30, 27) else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(23).toFloat()
                setColor(if (primary) Color.rgb(126, 226, 198) else Color.rgb(32, 42, 55))
            }
            column.addView(this, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) })
            setOnClickListener { action() }
        }
        main = button("开始记录", true) {
            if (graph.state.busy) send(RecordingCommand.PAUSE) else requestStart()
        }
        mark = button("标记此刻") {
            send(RecordingCommand.MARK)
            Toast.makeText(this, "已标记时间点", Toast.LENGTH_SHORT).show()
        }
        finish = button("结束本次记录") {
            AlertDialog.Builder(this).setMessage("保存并结束本次记录？已有录音会保留。")
                .setPositiveButton("结束") { _, _ -> send(RecordingCommand.FINISH) }
                .setNegativeButton("取消", null).show()
        }
        playback = button("试听最新片段") { playLatest() }
        settingsButton = button("设置") { startActivity(Intent(this, SettingsActivity::class.java)) }
        detail = label("", 11f, Color.LTGRAY).apply { setPadding(0, dp(10), 0, 0) }
        label("本地录音测试版 · ${packageManager.getPackageInfo(packageName, 0).versionName}\n下滑查看操作\n录音不自动上传", 10f, Color.GRAY)
    }
    private fun requestStart() {
        stopPlayback()
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
        else startRecording()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else Toast.makeText(this, "请允许麦克风权限后再开始", Toast.LENGTH_LONG).show()
        }
    }
    private fun startRecording() {
        try {
            graph.controls.send(RecordingCommand.START)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun send(action: RecordingCommand) { graph.controls.send(action); render() }
    private fun render() {
        val active = graph.state.busy
        settingsButton.isEnabled = !active
        val saved = graph.sessions.saved()
        val hasSession = saved.sessionId != null
        state.text = if (active) graph.state.status else saved.status
        state.setTextColor(if (graph.state.muted && active) Color.rgb(255, 180, 110) else Color.LTGRAY)
        val seconds = if (active) (SystemClock.elapsedRealtime() - graph.state.startedAt) / 1000 else 0
        timer.text = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        main.text = if (active) "暂停录音" else if (hasSession) "继续记录" else "开始记录"
        main.isEnabled = graph.state.status != "正在保存…"
        mark.visibility = if (active) View.VISIBLE else View.GONE
        mark.isEnabled = main.isEnabled
        finish.visibility = if (hasSession) View.VISIBLE else View.GONE
        finish.isEnabled = main.isEnabled
        playback.visibility = if (active) View.GONE else View.VISIBLE
        if (!active && SystemClock.elapsedRealtime() - lastScan > 5000) {
            val library = graph.sessions.library()
            latestFile = library.latestPath
            fileSummary = "${library.count} 个片段 · %.1f MB".format(library.bytes / 1_000_000.0)
            lastScan = SystemClock.elapsedRealtime()
        }
        if (deviceStats.isEmpty() || SystemClock.elapsedRealtime() - lastStats >= 30000) {
            val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            deviceStats = UiText.deviceStats(battery, filesDir.usableSpace)
            lastStats = SystemClock.elapsedRealtime()
        }
        detail.text = deviceStats +
            if (active) "音量 %.0f dBFS\n本段运行计时 · 可熄屏".format(graph.state.db) else "$fileSummary\n录音可通过 Mac 导出"
        playback.isEnabled = latestFile != null
    }
    private fun playLatest() {
        if (graph.playback.isPlaying) { stopPlayback(); return }
        val file = latestFile ?: return
        playback.text = "停止试听"
        graph.playback.play(file) { error ->
            playback.text = "试听最新片段"
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }
    private fun stopPlayback() { graph.playback.stop(); if (::playback.isInitialized) playback.text = "试听最新片段" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); stopPlayback(); super.onPause() }
}
