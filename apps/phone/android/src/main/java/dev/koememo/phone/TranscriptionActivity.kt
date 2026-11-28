package dev.koememo.phone

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.icu.text.BreakIterator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import dev.koememo.sync.sha256
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class TranscriptionActivity : Activity() {
    private lateinit var target: File
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var search: EditText
    private lateinit var player: RecordingPlayer
    private val io = Executors.newSingleThreadExecutor()
    private var catalog: AudioSession? = null // Owned by io.
    private var ready = false
    private var importing = false
    private val syncState by lazy { getSharedPreferences("sync_state", MODE_PRIVATE) }
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active" && !SyncService.running) load()
    }
    private var visible = false
    private val pages = linkedMapOf<File, Page>()
    private val adapter = Lines()
    private var shown = emptyList<Line>()
    private var animate: Line? = null
    private val changed: (File?) -> Unit = { file ->
        if (file == null) controls()
        else if ((target.isDirectory && file.parentFile == target) || target == file) load(file)
    }

    private data class Line(val clip: SessionClip, val start: Int, val original: String, val edit: String?, val prefix: String, val context: String) {
        val text get() = edit ?: original
        fun label(body: String) = prefix + body + context
    }
    private data class Page(val complete: Boolean, val lines: List<Line>, val error: String? = null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        target = File(intent.getStringExtra("path").orEmpty()).canonicalFile
        val directory = if (target.isDirectory) target else target.parentFile
        if (directory == null || !directory.toPath().startsWith(File(filesDir, "audio").canonicalFile.toPath()) ||
            !(target.isDirectory || target.isAudioSegment())) { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        layout.addView(navigationBar(if (target.isDirectory) "整场文字稿" else "本段文字稿", back = true).apply {
            subtitle = if (target.isDirectory) sessionTitle(target) else target.name
        })
        status = TextView(this)
        action = Button(this).apply { setOnClickListener { toggle() } }
        search = EditText(this).apply { setSingleLine(); hint = "搜索文字稿"; setText(state?.getString("query").orEmpty()) }
        layout.addView(action); layout.addView(status)
        player = RecordingPlayer(this)
        layout.addView(search)
        val list = ListView(this).apply {
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            adapter = this@TranscriptionActivity.adapter
            setOnItemClickListener { _, _, index, _ -> play(shown[index]) }
            setOnItemLongClickListener { _, _, index, _ -> edit(shown[index]); true }
        }
        layout.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(player)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) {}
        })
        setContentView(layout)
        controls()
    }
    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        visible = true
        player.resumeUpdates()
        TranscriptionJob.listeners.add(changed)
        syncState.registerOnSharedPreferenceChangeListener(syncListener)
        load()
    }
    override fun onStop() {
        TranscriptionJob.listeners.remove(changed)
        syncState.unregisterOnSharedPreferenceChangeListener(syncListener)
        visible = false
        if (::player.isInitialized) player.suspendUpdates()
        super.onStop()
    }
    override fun onDestroy() {
        io.shutdownNow()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        if (::search.isInitialized) state.putString("query", search.text.toString())
    }

    private fun load(changedFile: File? = null) {
        io.execute {
            try {
                val directory = if (target.isDirectory) target else target.parentFile!!
                val session = if (changedFile == null) {
                    AudioSession.load(directory, target.takeUnless { it == directory }).also { catalog = it }
                } else catalog ?: return@execute
                val changedHash = session.clips.firstOrNull { it.file == changedFile }?.hash
                val selected = session.clips.filter { (target == directory || it.file == target) &&
                    (changedFile == null || it.hash == changedHash) }
                val result = selected.associate { clip -> clip.file to try { readPage(clip) }
                    catch (e: Exception) { Page(false, emptyList(), "${clip.file.name} · ${e.localizedMessage}") } }
                ui {
                    val previousSize = pages[changedFile]?.lines?.size ?: 0
                    val redraw = changedFile == null || result.any { (file, page) -> pages[file]?.lines != page.lines }
                    if (changedFile == null) pages.clear()
                    pages.putAll(result)
                    if (changedFile != null) animate = pages[changedFile]?.lines?.takeIf { it.size > previousSize }?.lastOrNull()
                    ready = true
                    if (redraw) filter()
                    controls()
                }
            } catch (e: Exception) { ui { status.text = "无法读取文字稿 · ${e.localizedMessage}" } }
        }
    }
    private fun readPage(clip: SessionClip): Page {
        val checkpoint = LocalTranscription(this, clip)
        val data = checkpoint.snapshot()
        val edits = checkpoint.corrections()
        val windows = data.getJSONArray("windows")
        val clock = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        val lines = buildList {
            for (i in 0 until windows.length()) {
                val row = windows.getJSONObject(i)
                val start = row.getInt("start_ms")
                val text = row.getString("text")
                val edit = if (edits.has(start.toString())) edits.getString(start.toString()) else null
                if (text.isBlank() && edit == null) continue
                val span = clip.span?.takeUnless { clip.hashMismatch }
                val wall = span?.let { clip.run?.timeAt(it.file, start.toLong()) }
                val prefix = (wall?.let { clock.format(Date(it)) + "　" }.orEmpty()) +
                    "${clip.file.name}　${duration(start / 1000L)}\n"
                val context = span?.contextAt(start)?.description()?.let { "\n$it" }.orEmpty()
                add(Line(clip, start, text, edit, prefix, context))
            }
        }
        return Page(data.optBoolean("complete"), lines)
    }
    private fun filter() {
        val term = search.text.toString().trim()
        shown = pages.values.flatMap { it.lines }.filter { it.text.contains(term, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }
    private fun controls() {
        val active = TranscriptionJob.active(this)
        val mine = active == target.path
        val complete = pages.isNotEmpty() && pages.values.all { it.complete }
        val model = AsrModel.directory(this).isDirectory
        action.text = when {
            mine -> "暂停转写"
            active != null -> "其他录音正在转写"
            complete -> "已完成转写"
            !model -> "导入 SenseVoice 模型"
            target.isDirectory -> "开始／继续整场转写"
            else -> "开始／继续本段转写"
        }
        action.isEnabled = !importing && (mine || (active == null && ready && pages.isNotEmpty() && !complete))
        val last = TranscriptionJob.status(this, target.path)
        val detail = when {
            mine || last.isEmpty() -> last
            last.startsWith("正在转写") || last == "等待系统调度" || last.startsWith("系统暂缓") -> "任务已中断，可继续转写"
            else -> "上次任务 · $last"
        }
        status.text = listOfNotNull(detail.takeIf { it.isNotEmpty() },
            pages.values.firstNotNullOfOrNull { it.error }?.let { "读取失败 · $it" },
            if (ready) "已转写 ${pages.values.count { it.complete }}/${pages.size} 段 · 点文字回听，长按校订" else "正在读取录音…").joinToString("\n")
    }
    private fun toggle() {
        try {
            if (TranscriptionJob.active(this) == target.path) TranscriptionJob.pause(this, target)
            else if (AsrModel.directory(this).isDirectory) TranscriptionJob.start(this, target)
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        } catch (e: Exception) { status.text = e.localizedMessage }
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 1 || result != RESULT_OK) {
            return
        }
        val tree = data?.data ?: return
        importing = true
        controls()
        io.execute {
            val resultText = runCatching { AsrModel.import(this, tree); "模型已就绪" }.getOrElse { "导入失败 · ${it.message}" }
            ui { importing = false; controls(); status.text = resultText }
        }
    }
    private fun play(line: Line) {
        io.execute {
            val valid = runCatching { !line.clip.hashMismatch && line.clip.file.sha256() == line.clip.hash }.getOrDefault(false)
            ui {
                if (valid) player.play(line.clip.file, line.start, line.clip.span)
                else status.text = "原音缺失或校验失败，请重新同步"
            }
        }
    }
    private fun edit(line: Line) {
        val input = EditText(this).apply { setText(line.text) }
        fun save(replacement: String?) {
            io.execute {
                val outcome = runCatching { LocalTranscription(this, line.clip).correct(line.start, replacement) }
                ui { outcome.onSuccess { load(line.clip.file) }.onFailure { status.text = "保存校订失败 · ${it.localizedMessage}" } }
            }
        }
        AlertDialog.Builder(this).setTitle("校订文字").setMessage("识别原文　" + line.original).setView(input)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString()) }
            .setNeutralButton("恢复识别原文") { _, _ -> save(null) }.setNegativeButton("取消", null).show()
    }

    private inner class Lines : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(this@TranscriptionActivity).apply {
                textSize = 16f
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            (row.tag as? ValueAnimator)?.cancel()
            val line = shown[position]
            row.text = line.label(line.text)
            row.contentDescription = row.text
            if (line === animate && visible) {
                animate = null
                if (ValueAnimator.areAnimatorsEnabled() && !getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
                    val breaks = BreakIterator.getCharacterInstance().apply { setText(line.text) }
                    val ends = generateSequence(breaks.first()) { breaks.next().takeUnless { it == BreakIterator.DONE } }.toList()
                    row.tag = ValueAnimator.ofInt(0, ends.lastIndex).apply {
                        duration = (ends.lastIndex * 40L).coerceAtMost(2400L)
                        addUpdateListener { row.text = line.label(line.text.substring(0, ends[it.animatedValue as Int])) }
                        start()
                    }
                }
            }
            return row
        }
    }
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }
}
