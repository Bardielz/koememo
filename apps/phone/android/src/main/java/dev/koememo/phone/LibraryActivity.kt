package dev.koememo.phone

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import dev.koememo.sync.MarkPosition
import dev.koememo.sync.ContextWindow
import dev.koememo.sync.PlaybackSpan
import dev.koememo.sync.sessionTimeline
import dev.koememo.sync.sha256
import java.text.DateFormat
import java.io.*

class LibraryActivity : Activity() {
    private val syncState by lazy { getSharedPreferences("sync_state", MODE_PRIVATE) }
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderSync() }
    private lateinit var pause: Button
    private lateinit var status: TextView
    private lateinit var libraryHint: TextView
    private var loadGeneration = 0
    private lateinit var list: ListView
    private var exporting: File? = null
    private lateinit var sync: Button
    private lateinit var player: RecordingPlayer
    private val root get() = File(filesDir,"audio")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        layout.addView(TextView(this).apply { text = "KoeMemo"; textSize = 22f })
        status = TextView(this); sync = Button(this).apply { text = "从手表同步"; setOnClickListener {
            startForegroundService(Intent(this@LibraryActivity, SyncService::class.java))
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        } }
        layout.addView(Button(this).apply { text = "实验功能"; setOnClickListener { PhoneExperiments.show(this@LibraryActivity) } })
        layout.addView(sync)
        pause = Button(this).apply { text = "暂停同步"; setOnClickListener {
            startService(Intent(this@LibraryActivity, SyncService::class.java).setAction(SyncService.PAUSE))
        } }
        layout.addView(pause)
        layout.addView(status)
        player = RecordingPlayer(this)
        layout.addView(player)
        libraryHint = TextView(this).apply { text = "正在读取本地录音…" }
        layout.addView(libraryHint)
        exporting = state?.getString("exportFile")?.let(::File)
        list = ListView(this)
        layout.addView(list); setContentView(layout)
    }
    override fun onStart() {
        super.onStart()
        player.resumeUpdates()
        syncState.registerOnSharedPreferenceChangeListener(syncListener)
        if (!SyncService.running && syncState.contains(SyncService.PENDING)) {
            startForegroundService(Intent(this, SyncService::class.java).setAction(SyncService.RESUME))
        }
        renderSync()
        if (SyncService.running) refresh()
    }
    override fun onStop() {
        player.suspendUpdates()
        syncState.unregisterOnSharedPreferenceChangeListener(syncListener)
        super.onStop()
    }
    private fun renderSync() {
        val running = SyncService.running
        val saved = syncState.getString("status", "").orEmpty()
        status.text = if (!running && syncState.getBoolean("active", false)) "同步已中断，可继续同步" else saved
        sync.isEnabled = !running
        pause.isEnabled = running || syncState.contains(SyncService.PENDING)
        if (!running) refresh()
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
    private fun refresh() {
        val generation = ++loadGeneration
        Thread {
            val result = runCatching { root.walkTopDown().filter { it.isFile && it.extension in setOf("aac","ogg") }.sortedByDescending { it.path }.toList() }
            runOnUiThread {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { showFiles(it) }.onFailure { libraryHint.text = "读取录音失败：" + it.javaClass.simpleName }
            }
        }.start()
    }
    private fun showFiles(files: List<File>) {
        libraryHint.text = if (files.isEmpty()) "手机上还没有录音，点「从手表同步」开始。" else "本地录音（${files.size}）"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files.map { it.parentFile!!.name + "/" + it.name })
        list.setOnItemClickListener { _, _, index, _ ->
            val file = files[index]
            android.app.AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("播放", "导出", "本地转写", "本次录音的标记", "录音上下文")) { _, choice ->
                if (choice == 0) {
                    player.play(file)
                } else if (choice == 2) {
                    startActivity(Intent(this, TranscriptionActivity::class.java).putExtra("path", file.path))
                } else if (choice == 3) {
                    showMarks(file)
                } else if (choice == 4) {
                    showContext(file)
                } else {
                    exporting = file
                    startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = if (file.extension == "ogg") "audio/ogg" else "audio/aac"
                        putExtra(android.content.Intent.EXTRA_TITLE, file.name)
                    }, 1)
                }
            }.show()
        }
    }
    private data class ContextItem(val span: PlaybackSpan, val window: ContextWindow, val time: Long?)

    private fun showContext(file: File) {
        Thread {
            val result = runCatching {
                sessionTimeline(file).flatMap { run ->
                    run.spans.flatMap { span -> span.windows.values.map { window ->
                        ContextItem(span, window, run.timeAt(span.file, window.startMs.toLong()))
                    } }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = result.getOrElse { status.text = "无法读取录音上下文"; return@runOnUiThread }
                if (items.isEmpty()) status.text = "此会话暂无上下文。开启手表的运动和心率实验并同步后可在此查看。"
                else contextDialog(file, items, false)
            }
        }.start()
    }

    private fun contextDialog(file: File, items: List<ContextItem>, byMotion: Boolean) {
        val sorted = if (byMotion) items.sortedByDescending { it.window.meanMotion } else items
        val clock = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        val adapter = object : ArrayAdapter<ContextItem>(this, android.R.layout.simple_list_item_1, sorted) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val label = super.getView(position, convertView, parent) as TextView
                val item = sorted[position]
                val t = item.time?.let { clock.format(java.util.Date(it)) } ?: item.span.file
                val sec = item.window.startMs / 1000
                label.isSingleLine = false
                label.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                label.text = "%s　%d:%02d\n%s".format(t, sec / 60, sec % 60, item.window.description())
                return label
            }
        }
        android.app.AlertDialog.Builder(this).setTitle(if (byMotion) "录音上下文 · 活动由强到弱" else "录音上下文 · 时间顺序")
            .setAdapter(adapter) { _, i ->
                val item = sorted[i]
                playVerified(File(file.parentFile, item.span.file), item.window.startMs, item.span)
            }.setNeutralButton(if (byMotion) "按时间" else "按活动强度") { _, _ -> contextDialog(file, items, !byMotion) }
            .setNegativeButton("关闭", null).show()
    }

    private fun playVerified(audio: File, offsetMs: Int, span: PlaybackSpan) {
        Thread {
            val verified = runCatching { audio.sha256() == span.hash }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (verified) player.play(audio, offsetMs, span)
                else status.text = "对应原音缺失或校验失败，请重新同步"
            }
        }.start()
    }
    private fun showMarks(file: File) {
        Thread {
            val result = runCatching { sessionTimeline(file) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val runs = result.getOrElse {
                    status.text = "无法读取本次录音的时间轴"
                    return@runOnUiThread
                }
                val marks = runs.flatMap { run -> run.marks.map { mark -> mark to run.locate(mark) } }
                if (marks.isEmpty()) {
                    status.text = if (runs.isEmpty()) "这次录音尚无可用时间轴" else "本次录音没有标记"
                    return@runOnUiThread
                }
                val clock = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                val spans = runs.flatMap { it.spans }.associateBy { it.file }
                val items = marks.map { (mark, position) ->
                    val where = when (position) {
                        is MarkPosition.Audio -> listOfNotNull("回听", spans[position.file]?.contextAt(position.offsetMs)?.description()).joinToString("\n")
                        MarkPosition.Omitted -> "此处静音已省略"
                        MarkPosition.Unknown -> "原音位置未知"
                    }
                    clock.format(java.util.Date(mark.wallMs)) + "　" + where
                }
                android.app.AlertDialog.Builder(this).setTitle("本次录音的标记")
                    .setItems(items.toTypedArray()) { _, i ->
                        val position = marks[i].second as? MarkPosition.Audio ?: return@setItems
                        val audio = File(file.parentFile, position.file)
                        spans[position.file]?.takeIf { it.hash == position.hash }?.let { playVerified(audio, position.offsetMs, it) }
                    }.show()
            }
        }.start()
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("exportFile", exporting?.path)
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(request: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(request,result,data)
        val file = exporting ?: return
        if (request != 1 || result != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val message = runCatching {
                requireNotNull(contentResolver.openOutputStream(uri)).use { dest -> file.inputStream().use { it.copyTo(dest) } }
                "已导出 " + file.name
            }.getOrElse { "导出失败：" + it.message }
            runOnUiThread { status.text = message }
        }.start()
    }
}
