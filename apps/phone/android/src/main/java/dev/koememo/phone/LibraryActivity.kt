package dev.koememo.phone

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Intent
import android.content.SharedPreferences
import dev.koememo.sync.MarkPosition
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
            android.app.AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("播放", "导出", "本地转写", "本次录音的标记")) { _, choice ->
                if (choice == 0) {
                    player.play(file)
                } else if (choice == 2) {
                    startActivity(Intent(this, TranscriptionActivity::class.java).putExtra("path", file.path))
                } else if (choice == 3) {
                    showMarks(file)
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
                val items = marks.map { (mark, position) ->
                    val where = when (position) {
                        is MarkPosition.Audio -> "回听"
                        MarkPosition.Omitted -> "此处静音已省略"
                        MarkPosition.Unknown -> "原音位置未知"
                    }
                    clock.format(java.util.Date(mark.wallMs)) + "　" + where
                }
                android.app.AlertDialog.Builder(this).setTitle("本次录音的标记")
                    .setItems(items.toTypedArray()) { _, i ->
                        val position = marks[i].second as? MarkPosition.Audio ?: return@setItems
                        val audio = File(file.parentFile, position.file)
                        Thread {
                            val verified = runCatching { audio.sha256() == position.hash }.getOrDefault(false)
                            runOnUiThread play@{
                                if (isDestroyed || isFinishing) return@play
                                if (verified) player.play(audio, position.offsetMs)
                                else status.text = "对应原音缺失或校验失败，请重新同步"
                            }
                        }.start()
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
