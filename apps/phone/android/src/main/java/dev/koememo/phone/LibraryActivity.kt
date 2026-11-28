package dev.koememo.phone

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import dev.koememo.sync.ContextWindow
import dev.koememo.sync.MarkPosition
import dev.koememo.sync.PlaybackSpan
import dev.koememo.sync.TimelineJournal
import dev.koememo.sync.sha256
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LibraryActivity : AppCompatActivity() {
    private val root get() = File(filesDir, "audio")
    private val syncState by lazy { getSharedPreferences("sync_state", MODE_PRIVATE) }
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (directory == null) renderSync()
        if (key == "active" && !SyncService.running) refresh()
    }
    private val io = Executors.newSingleThreadExecutor()
    private var directory: File? = null
    private val overview get() = directory != null && !intent.getBooleanExtra("files", false)
    private var restoreFile: File? = null
    private var restoreMs = 0
    private var generation = 0
    private var playRequest = 0
    private var returningFromSession = false
    private var all = emptyList<BrowserRow>()
    private var shown = emptyList<BrowserRow>()
    private var hashes = emptyMap<String, String>()
    private var spans = emptyMap<String, PlaybackSpan>()
    private var orderByGroup = false
    private var loadedDirectoryStamp: Long? = null
    private var exporting: File? = null
    private var anchorPath: String? = null
    private var anchorTop = 0
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var syncAction: MenuItem
    private lateinit var pausePending: MenuItem
    private lateinit var search: SearchView
    private lateinit var player: RecordingPlayer
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val adapter = Rows()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        intent.getStringExtra("segments")?.let {
            val dir = File(it).canonicalFile
            if (dir == root.canonicalFile || !dir.toPath().startsWith(root.canonicalFile.toPath()) || !dir.isDirectory) { finish(); return }
            directory = dir
        }
        exporting = state?.getString("exportFile")?.let(::File)
        anchorPath = state?.getString("anchor")
        anchorTop = state?.getInt("top") ?: 0
        orderByGroup = state?.getBoolean("orderByGroup") ?: false
        returningFromSession = state?.getBoolean("returningFromSession") ?: false

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val dir = directory
        val toolbar = navigationBar(if (dir == null) "录音库" else if (overview) "录音" else "音频文件", dir != null)
        layout.addView(toolbar)
        if (dir != null) toolbar.subtitle = sessionTitle(dir)
        if (overview && dir != null) {
            restoreFile = state?.getString("playingFile")?.let(::File)
            restoreMs = state?.getInt("playingOffset") ?: 0
            toolbar.action("音频文件") {
                player.pauseForNavigation()
                startActivity(Intent(this, LibraryActivity::class.java).putExtra("segments", dir.path).putExtra("files", true))
            }
            toolbar.action("录制详情") { showDetails(dir) }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(12))
            }
            layout.addView(ScrollView(this).apply { addView(content) })
            player = RecordingPlayer(this)
            content.addView(player)
            heading = TextView(this).apply { text = "正在读取录音…"; setPadding(0, dp(6), 0, dp(10)) }
            content.addView(heading)
            fun entry(label: String, onClick: () -> Unit) = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setOnClickListener { onClick() }
                content.addView(this)
            }
            entry("文字稿") { openTranscript(dir) }
            entry("本次录音的标记") { showMarks(dir) }
            entry("运动与心率") { showContext(dir) }
            status = TextView(this).apply { visibility = View.GONE }
            content.addView(status)
            setContentView(layout)
            return
        }
        if (dir == null) {
            syncAction = toolbar.action("从手表同步", true) { if (SyncService.running) pauseSync() else startSync() }
            pausePending = toolbar.action("暂停待恢复的同步") { pauseSync() }
            toolbar.action("实验功能") { PhoneExperiments.show(this) }
        }
        search = SearchView(this).apply {
            isIconifiedByDefault = false
            queryHint = if (dir == null) "按会话日期或名称查找" else "按时间、片段号或文件名查找"
            setQuery(state?.getString("query").orEmpty(), false)
        }
        layout.addView(search)
        status = TextView(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            visibility = View.GONE
        }
        layout.addView(status)
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        heading = TextView(this).apply { text = if (dir == null) "录音会话" else "录音片段"; textSize = 16f }
        tools.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        if (dir != null) {
            val sort = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = orderLabel()
                setOnClickListener { view ->
                    PopupMenu(this@LibraryActivity, view).apply {
                        menu.add("最近存入在前").setOnMenuItemClickListener { changeOrder(false, view as Button); true }
                        menu.add("按录制轮次和片段").setOnMenuItemClickListener { changeOrder(true, view as Button); true }
                    }.show()
                }
            }
            tools.addView(sort)
        }
        layout.addView(tools)
        list = ListView(this).apply {
            adapter = this@LibraryActivity.adapter
            isFastScrollEnabled = true
            itemsCanFocus = true
        }
        empty = TextView(this).apply { gravity = Gravity.CENTER; text = "正在读取录音…" }
        val body = FrameLayout(this).apply { addView(list); addView(empty) }
        list.emptyView = empty
        layout.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        player = RecordingPlayer(this)
        layout.addView(player)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean { search.clearFocus(); return true }
            override fun onQueryTextChange(newText: String?): Boolean { filter(reset = true); return true }
        })
        setContentView(layout)
        layout.requestFocus()
    }

    private fun orderLabel() = if (orderByGroup) "按录制轮次 ▾" else "最近存入 ▾"
    private fun changeOrder(group: Boolean, button: Button) {
        if (group == orderByGroup) return
        orderByGroup = group
        button.text = orderLabel()
        filter(reset = true)
    }

    private fun startSync() {
        startForegroundService(Intent(this, SyncService::class.java))
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
    }
    private fun pauseSync() = startService(Intent(this, SyncService::class.java).setAction(SyncService.PAUSE))
    private fun renderSync() {
        val running = SyncService.running
        val pending = syncState.contains(SyncService.PENDING)
        syncAction.title = when { running -> "暂停同步"; pending -> "重试同步"; else -> "从手表同步" }
        pausePending.isVisible = pending && !running
        val saved = syncState.getString("status", "").orEmpty()
        message(if (!running && syncState.getBoolean("active", false)) "同步已中断，可继续同步" else saved)
    }
    private fun message(value: String) {
        status.text = value
        status.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }
    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        player.resumeUpdates()
        syncState.registerOnSharedPreferenceChangeListener(syncListener)
        if (directory == null) {
            if (!returningFromSession && !SyncService.running && syncState.contains(SyncService.PENDING))
                startForegroundService(Intent(this, SyncService::class.java).setAction(SyncService.RESUME))
            returningFromSession = false
            renderSync()
        }
        refresh()
    }
    override fun onStop() {
        playRequest++
        if (::player.isInitialized) player.suspendUpdates()
        syncState.unregisterOnSharedPreferenceChangeListener(syncListener)
        super.onStop()
    }
    override fun onDestroy() {
        io.shutdownNow()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun refresh() {
        val request = ++generation
        val dir = directory
        val previousStamp = loadedDirectoryStamp
        io.execute {
            try {
                val stamp = dir?.lastModified()
                if (stamp != null && stamp > 0 && stamp == previousStamp) return@execute
                if (overview && dir != null) {
                    val playlist = SessionPlayback.load(dir)
                    runOnUiThread {
                        if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                        val resume = player.currentFile ?: restoreFile
                        val ms = if (player.currentFile != null) player.currentOffset else restoreMs
                        player.bind(playlist, resume, ms)
                        restoreFile = null
                        heading.text = listOf(if (playlist.tracks.isEmpty()) "手机上还没有可回听的原音" else "", playlist.note)
                            .filter(String::isNotEmpty).joinToString("\n")
                        heading.visibility = if (heading.text.isEmpty()) View.GONE else View.VISIBLE
                        loadedDirectoryStamp = stamp
                    }
                    return@execute
                }
                var expected = emptyMap<String, String>()
                var mapped = emptyMap<String, PlaybackSpan>()
                val rows = if (dir == null) {
                    root.walkTopDown().filter { it.isAudioSegment() || it.name == SyncCatalog.FILE }
                        .groupBy { it.parentFile!! }.map { (session, files) -> sessionRow(session, files.count { it.name != SyncCatalog.FILE }) }
                        .sortedWith(compareByDescending<BrowserRow> { it.keywords }.thenBy { it.file.path })
                } else {
                    val hasTimeline = File(dir, TimelineJournal.FILE).isFile
                    val session = if (hasTimeline) AudioSession.load(dir) else null
                    val clips = session?.clips.orEmpty()
                    val catalog = session?.catalog ?: runCatching { SyncCatalog.read(dir) }.getOrNull()
                    val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    expected = session?.runs.orEmpty().flatMap { it.spans }.associate { it.file to it.hash } + catalog?.hashes.orEmpty()
                    mapped = session?.mapped.orEmpty().associate { it.file.name to it.span!! }
                    val byFile = clips.associateBy { it.file }
                    val files = session?.clips?.map { it.file } ?: localSegments(dir)
                    files.map { file ->
                        val clip = byFile[file]
                        val wall = clip?.span?.takeUnless { clip.hashMismatch }?.let { clip.run?.timeAt(it.file, 0) }
                        val modified = file.lastModified()
                        segmentRow(file, modified, clock.format(Date(modified)), wall?.let { clock.format(Date(it)) })
                    }
                }
                runOnUiThread {
                    if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                    all = rows; hashes = expected; spans = mapped
                    loadedDirectoryStamp = stamp
                    filter(reset = false)
                }
            } catch (e: Exception) {
                runOnUiThread { if (request == generation && !isDestroyed) {
                    if (overview) heading.text = "读取录音失败" else empty.text = "读取录音失败"
                    message(e.localizedMessage.orEmpty())
                } }
            }
        }
    }
    private fun filter(reset: Boolean) {
        val term = search.query.toString().trim().lowercase(Locale.ROOT)
        val pos = if (reset) null else anchorPath ?: shown.getOrNull(list.firstVisiblePosition)?.file?.path
        val top = if (anchorPath != null) anchorTop else list.getChildAt(0)?.top ?: 0
        val matches = if (term.isEmpty()) all else all.filter { it.matches(term) }
        shown = if (directory == null || orderByGroup) matches
            else matches.sortedWith(compareByDescending<BrowserRow> { it.storedMs }.thenByDescending { it.file.name })
        heading.text = (if (directory == null) "录音会话" else "录音片段") + "  ·  ${shown.size}"
        empty.text = when {
            term.isNotEmpty() -> "没有匹配的录音"
            directory != null -> "这次会话还没有已同步的片段"
            else -> "手机上还没有录音，点「从手表同步」获取。"
        }
        adapter.notifyDataSetChanged()
        val index = shown.indexOfFirst { it.file.path == pos }
        if (index >= 0) list.setSelectionFromTop(index, top) else if (reset) list.setSelection(0)
        if (all.isNotEmpty() || reset) anchorPath = null
    }

    private inner class RowView : LinearLayout(this@LibraryActivity) {
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(4), dp(10))
            isFocusable = true
        }
        val title = TextView(context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }
        val detail = TextView(context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }
        val more = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply { setImageResource(R.drawable.ic_more) }
        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            val attr = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
            main.setBackgroundResource(attr.resourceId)
            main.addView(title); main.addView(detail)
            addView(main, LayoutParams(0, -2, 1f))
            if (directory != null) addView(more, LayoutParams(dp(52), dp(52)))
        }
    }
    private inner class Rows : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? RowView ?: RowView()
            val item = getItem(position)
            row.title.text = item.title
            row.detail.text = item.subtitle
            row.main.contentDescription = "${item.title}，${item.subtitle}"
            row.main.setOnClickListener {
                search.clearFocus()
                if (directory == null) {
                    returningFromSession = true
                    startActivity(Intent(this@LibraryActivity, LibraryActivity::class.java).putExtra("segments", item.file.path))
                } else playSegment(item.file)
            }
            if (directory != null) {
                row.more.contentDescription = "${item.title}的更多操作"
                row.more.setOnClickListener { anchor ->
                    PopupMenu(this@LibraryActivity, anchor).apply {
                        menu.add("本段文字稿").setOnMenuItemClickListener { openTranscript(item.file); true }
                        menu.add("导出音频").setOnMenuItemClickListener { export(item.file); true }
                    }.show()
                }
            }
            return row
        }
    }
    private fun playSegment(file: File) {
        val span = spans[file.name]
        val hash = hashes[file.name] ?: span?.hash
        if (hash == null) {
            playRequest++
            player.play(file)
        } else playVerified(file, 0, span, hash)
    }
    private fun openTranscript(target: File) {
        if (::search.isInitialized) search.clearFocus()
        playRequest++
        player.pauseForNavigation()
        startActivity(Intent(this, TranscriptionActivity::class.java).putExtra("path", target.path))
    }
    private fun export(file: File) {
        exporting = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (file.extension == "ogg") "audio/ogg" else "audio/aac"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }, 1)
    }
    private fun showDetails(dir: File) {
        io.execute {
            val result = runCatching { describeSession(dir, AudioSession.load(dir)) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) MaterialAlertDialogBuilder(this).setTitle("录制详情")
                    .setMessage(result.getOrElse { "无法读取录制详情" }).setPositiveButton("关闭", null).show()
            }
        }
    }
    private data class ContextItem(val span: PlaybackSpan, val window: ContextWindow, val time: Long?)

    private fun showContext(dir: File) {
        Thread {
            val result = runCatching {
                directoryTimeline(dir).flatMap { run ->
                    run.spans.flatMap { span -> span.windows.values.map { window ->
                        ContextItem(span, window, run.timeAt(span.file, window.startMs.toLong()))
                    } }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val items = result.getOrElse { message("无法读取录音上下文"); return@runOnUiThread }
                if (items.isEmpty()) message("这次会话暂无运动或心率数据")
                else contextDialog(dir, items, false)
            }
        }.start()
    }

    private fun contextDialog(dir: File, items: List<ContextItem>, byMotion: Boolean) {
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
        MaterialAlertDialogBuilder(this).setTitle(if (byMotion) "录音上下文 · 活动由强到弱" else "录音上下文 · 时间顺序")
            .setAdapter(adapter) { _, i ->
                val item = sorted[i]
                playVerified(File(dir, item.span.file), item.window.startMs, item.span, item.span.hash)
            }.setNeutralButton(if (byMotion) "按时间" else "按活动强度") { _, _ -> contextDialog(dir, items, !byMotion) }
            .setNegativeButton("关闭", null).show()
    }

    private fun playVerified(audio: File, offsetMs: Int, span: PlaybackSpan?, expected: String) {
        val request = ++playRequest
        Thread {
            val verified = runCatching { audio.sha256() == expected }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed || isFinishing || request != playRequest) return@runOnUiThread
                if (verified && overview) {
                    if (!player.playFrom(audio, offsetMs)) message("原音尚未就绪，请稍后重试")
                } else if (verified) player.play(audio, offsetMs, span)
                else message("对应原音缺失或校验失败，请重新同步")
            }
        }.start()
    }
    private fun showMarks(dir: File) {
        Thread {
            val result = runCatching { directoryTimeline(dir) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val runs = result.getOrElse {
                    message("无法读取本次录音的时间轴")
                    return@runOnUiThread
                }
                val marks = runs.flatMap { run -> run.marks.map { mark -> mark to run.locate(mark) } }
                if (marks.isEmpty()) {
                    message(if (runs.isEmpty()) "这次录音尚无可用时间轴" else "本次录音没有标记")
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
                MaterialAlertDialogBuilder(this).setTitle("本次录音的标记")
                    .setItems(items.toTypedArray()) { _, i ->
                        val position = marks[i].second as? MarkPosition.Audio ?: return@setItems
                        val audio = File(dir, position.file)
                        spans[position.file]?.takeIf { it.hash == position.hash }?.let { playVerified(audio, position.offsetMs, it, it.hash) }
                    }.show()
            }
        }.start()
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        if (overview && ::player.isInitialized) {
            state.putString("playingFile", player.currentFile?.path ?: restoreFile?.path)
            state.putInt("playingOffset", if (player.currentFile != null) player.currentOffset else restoreMs)
        }
        if (!::list.isInitialized) return
        state.putString("query", search.query.toString())
        state.putString("anchor", shown.getOrNull(list.firstVisiblePosition)?.file?.path ?: anchorPath)
        state.putInt("top", list.getChildAt(0)?.top ?: anchorTop)
        state.putBoolean("orderByGroup", orderByGroup)
        state.putBoolean("returningFromSession", returningFromSession)
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
            runOnUiThread { message(message) }
        }.start()
    }
}
