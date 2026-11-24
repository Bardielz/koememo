package dev.koememo.phone

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Intent
import android.content.SharedPreferences
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
            android.app.AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("播放", "导出")) { _, choice ->
                if (choice == 0) {
                    player.play(file)
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
