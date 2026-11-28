package dev.koememo.phone

import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.google.android.material.button.MaterialButton
import dev.koememo.sync.sha256
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class TranscriptionActivity : AppCompatActivity() {
    private lateinit var target: File
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var modelAction: Button
    private var choice = AsrChoice.legacy
    private val modelSettings by lazy { getSharedPreferences("asr-model", MODE_PRIVATE) }
    private lateinit var search: EditText
    private lateinit var player: RecordingPlayer
    private val io = Executors.newSingleThreadExecutor()
    private var catalog: AudioSession? = null // Owned by io.
    private lateinit var speakerData: JSONObject
    private lateinit var speakerNames: JSONObject
    private var ready = false
    private var importing = false
    private val syncState by lazy { getSharedPreferences("sync_state", MODE_PRIVATE) }
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active" && !SyncService.running) load()
    }
    private var visible = false
    private val pages = linkedMapOf<File, Page>()
    private val adapter = Lines()
    private val readingSettings by lazy { getSharedPreferences("transcript-reading", MODE_PRIVATE) }
    private lateinit var readingArea: LinearLayout
    private lateinit var rail: ParagraphRail
    private lateinit var list: ListView
    private var shown = emptyList<List<TranscriptLine>>()
    private var animate: TranscriptLine? = null
    private val changed: (File?) -> Unit = { file ->
        if (file == null) controls()
        else if ((target.isDirectory && file.parentFile == target) || target == file) load(file)
    }

    private data class Page(val complete: Boolean, val lines: List<TranscriptLine>, val error: String? = null)
    private var touched: TranscriptLine? = null
    private val clock = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    private fun time(line: TranscriptLine) = line.wall?.let { clock.format(Date(it)) } ?: sourceTime(line.start)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        target = File(intent.getStringExtra("path").orEmpty()).canonicalFile
        val directory = if (target.isDirectory) target else target.parentFile
        val root = File(filesDir, "audio").canonicalFile
        if (directory == null || directory == root || !directory.toPath().startsWith(root.toPath()) ||
            !(target.isDirectory || target.isAudioSegment())) { finish(); return }
        choice = TranscriptionJob.choice(this, target) ?: AsrChoice.from(modelSettings.getString("selected", null))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        layout.addView(navigationBar(if (target.isDirectory) "整场文字稿" else "本段文字稿", back = true).apply {
            subtitle = if (target.isDirectory) sessionTitle(target) else target.name
            action("段落导航位置") { chooseRailSide() }
            if (target.isDirectory && PhoneExperiments.enabled(this@TranscriptionActivity)) action("文字稿实验") {
                player.pauseForNavigation()
                startActivity(Intent(this@TranscriptionActivity, TranscriptLabActivity::class.java)
                    .putExtra("directory", directory.path).putExtra("engine", choice.key))
            }
            if (target.isDirectory) action("发言人", true) {
                player.pauseForNavigation()
                startActivity(Intent(this@TranscriptionActivity, SpeakerActivity::class.java)
                    .putExtra("directory", directory.path).putExtra("engine", choice.key))
            }
        })
        modelAction = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setOnClickListener { selectModel() }
        }
        layout.addView(modelAction)
        status = TextView(this).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        action = MaterialButton(this).apply { setOnClickListener { onTranscriptionAction() } }
        search = EditText(this).apply { setSingleLine(); hint = "搜索文字稿"; setText(state?.getString("query").orEmpty()) }
        layout.addView(action); layout.addView(status)
        player = RecordingPlayer(this)
        layout.addView(search)
        list = ListView(this).apply {
            divider = null
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            adapter = this@TranscriptionActivity.adapter
            setOnItemClickListener { _, _, index, _ -> chooseWindow(shown[index], false) }
            setOnItemLongClickListener { _, _, index, _ -> chooseWindow(shown[index], true); true }
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val position = pointToPosition(event.x.toInt(), event.y.toInt())
                    val row = getChildAt(position - firstVisiblePosition) as? TextView
                    val offset = row?.getOffsetForPosition(event.x - row.left, event.y - row.top)
                    val text = row?.text as? android.text.Spanned
                    touched = if (text != null && offset != null && offset in 0..text.length)
                        text.getSpans(offset, offset, TranscriptLine::class.java).lastOrNull() else null
                }
                false
            }
        }
        rail = ParagraphRail(this) { index -> list.setSelectionFromTop(index, 0) }.apply { visibility = View.GONE }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                val bottom = first > 0 && first + visible == total && !view.canScrollVertically(1)
                rail.update(total, if (bottom) total - 1 else first)
            }
        })
        readingArea = LinearLayout(this).apply {
            addView(list, LinearLayout.LayoutParams(0, -1, 1f))
        }
        placeRail(readingSettings.getBoolean("rail_right", false))
        layout.addView(readingArea, LinearLayout.LayoutParams(-1, 0, 1f))
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

    private fun chooseRailSide() {
        MaterialAlertDialogBuilder(this).setTitle("段落导航位置")
            .setSingleChoiceItems(arrayOf("左侧", "右侧"), if (readingSettings.getBoolean("rail_right", false)) 1 else 0) { dialog, index ->
                val right = index == 1
                readingSettings.edit().putBoolean("rail_right", right).apply()
                placeRail(right)
                dialog.dismiss()
            }.show()
    }
    private fun placeRail(right: Boolean) {
        // These are physical sides, even when the document is displayed in a RTL locale.
        if (rail.parent === readingArea) readingArea.removeView(rail)
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        readingArea.addView(rail, if (right != rtl) 1 else 0,
            LinearLayout.LayoutParams(rail.dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun load(changedFile: File? = null) {
        val selectedModel = choice
        io.execute {
            try {
                val directory = if (target.isDirectory) target else target.parentFile!!
                val session = if (changedFile == null) {
                    val speakers = SpeakerStore(this, directory, SpeakerMethod.forFamily(selectedModel.family))
                    speakerData = speakers.analysis()
                    speakerNames = speakers.names()
                    AudioSession.load(directory, target.takeUnless { it == directory }).also { catalog = it }
                } else catalog ?: return@execute
                val changedHash = session.clips.firstOrNull { it.file == changedFile }?.hash
                val selected = session.clips.filter { (target == directory || it.file == target) &&
                    (changedFile == null || it.hash == changedHash) }
                val result = selected.associate { clip -> clip.file to try { readPage(clip, selectedModel) }
                    catch (e: Exception) { Page(false, emptyList(), "${clip.file.name} · ${e.localizedMessage}") } }
                ui {
                    if (selectedModel != choice) return@ui
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
    private fun readPage(clip: SessionClip, model: AsrChoice): Page {
        val checkpoint = LocalTranscription(this, clip, model)
        val data = checkpoint.snapshot()
        return Page(data.optBoolean("complete"), transcriptLines(this, clip, model, speakerData, speakerNames, data))
    }
    private fun filter() {
        val term = search.text.toString().trim()
        shown = transcriptParagraphs(pages.values.flatMap { it.lines }).filter { paragraph ->
            term.isEmpty() || paragraph.any { it.text.contains(term, true) || it.speaker.contains(term, true) }
        }
        adapter.notifyDataSetChanged()
        rail.update(shown.size, list.firstVisiblePosition)
    }
    private fun chooseWindow(lines: List<TranscriptLine>, editing: Boolean) {
        fun open(line: TranscriptLine) = if (editing) edit(line) else play(line)
        val hit = touched
        touched = null
        if (hit != null && hit in lines) { open(hit); return }
        if (lines.size == 1) { open(lines.first()); return }
        if (!editing) { open(lines.firstOrNull { search.text.isNotBlank() && it.text.contains(search.text, true) } ?: lines.first()); return }
        MaterialAlertDialogBuilder(this).setTitle("选择要校订的原文")
            .setItems(lines.map { time(it) + "　" + it.text }.toTypedArray()) { _, i -> edit(lines[i]) }.show()
    }
    private fun controls() {
        val active = TranscriptionJob.active(this)
        val mine = active == target.path
        val complete = pages.isNotEmpty() && pages.values.all { it.complete }
        modelAction.text = choice.label
        modelAction.contentDescription = "选择转写模型和处理器，当前 ${choice.label}"
        modelAction.isEnabled = !mine && !importing
        val model = choice.family.directory(this).isDirectory
        action.text = when {
            mine -> "暂停转写"
            active != null -> "其他录音正在转写"
            complete -> "已完成转写"
            !model -> "导入 ${choice.family.label} 模型"
            target.isDirectory -> "开始／继续整场转写"
            else -> "开始／继续本段转写"
        }
        action.isEnabled = !importing && (mine || (active == null && ready && pages.isNotEmpty() && !complete))
        val last = TranscriptionJob.status(this, target.path, choice)
        val detail = when {
            mine || last.isEmpty() -> last
            last.startsWith("正在转写") || last == "等待系统调度" || last.startsWith("系统暂缓") -> "任务已中断，可继续转写"
            else -> "上次任务 · $last"
        }
        val progress = when {
            !ready -> "正在读取录音…"
            pages.isEmpty() -> "等待音频同步"
            target.isDirectory && !complete -> "完整转写进度 ${100L * pages.values.count { it.complete } / pages.size}% · 点文字回听，长按校订"
            else -> "点文字回听，长按校订"
        }
        status.text = listOfNotNull((if (mine && detail.startsWith("正在转写")) "正在转写" else detail).takeIf { it.isNotEmpty() },
            pages.values.firstNotNullOfOrNull { it.error }?.let { "读取失败 · $it" },
            progress).joinToString("\n")
    }
    private fun selectModel() {
        val families = AsrFamily.entries
        MaterialAlertDialogBuilder(this).setTitle("转写模型")
            .setSingleChoiceItems(families.map { it.label }.toTypedArray(), families.indexOf(choice.family)) { dialog, index ->
                dialog.dismiss()
                val family = families[index]
                if (family == AsrFamily.SENSE) changeModel(AsrChoice.legacy)
                else {
                    val processors = AsrProcessor.entries
                    MaterialAlertDialogBuilder(this).setTitle("${family.label} · 推理处理器")
                        .setSingleChoiceItems(processors.map { it.label }.toTypedArray(), processors.indexOf(choice.processor)) { selector, which ->
                            selector.dismiss()
                            changeModel(AsrChoice(family, processors[which]))
                        }.setNegativeButton("取消", null).show()
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun changeModel(selected: AsrChoice) {
        if (choice == selected) return
        choice = selected
        modelSettings.edit().putString("selected", choice.key).apply()
        pages.clear()
        ready = false
        filter()
        controls()
        load()
    }
    private fun onTranscriptionAction() {
        try {
            if (TranscriptionJob.active(this) == target.path) TranscriptionJob.pause(this, target)
            else if (choice.family.directory(this).isDirectory) TranscriptionJob.start(this, target, choice)
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
            val resultText = runCatching { choice.family.install(this, tree); "模型已就绪" }.getOrElse { "导入失败 · ${it.message}" }
            ui { importing = false; controls(); status.text = resultText }
        }
    }
    private fun play(line: TranscriptLine) {
        io.execute {
            val valid = runCatching { !line.clip.hashMismatch && line.clip.file.sha256() == line.clip.hash }.getOrDefault(false)
            ui {
                if (valid) player.play(line.clip.file, line.start, line.clip.span, "这段原话")
                else status.text = "原音缺失或校验失败，请重新同步"
            }
        }
    }
    private fun edit(line: TranscriptLine) {
        val selectedModel = choice
        val input = EditText(this).apply { setText(line.text) }
        fun save(replacement: String?) {
            io.execute {
                val outcome = runCatching { LocalTranscription(this, line.clip, selectedModel).correct(line.start, replacement) }
                ui { outcome.onSuccess { load(line.clip.file) }.onFailure { status.text = "保存校订失败 · ${it.localizedMessage}" } }
            }
        }
        MaterialAlertDialogBuilder(this).setTitle("校订文字").setMessage("识别原文　" + line.original).setView(input)
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
            val paragraph = shown[position]
            val first = paragraph.first()
            val heading = first.speaker + "　" + time(first)
            val body = StringBuilder()
            var lastStart = 0
            val offsets = mutableListOf<Int>()
            for (line in paragraph) {
                body.append(paragraphSeparator(body.toString(), line.text))
                lastStart = body.length
                offsets.add(lastStart)
                body.append(line.text)
            }
            val context = paragraph.map { it.context }.filter(String::isNotBlank).distinct().joinToString(" · ")
            fun render(text: CharSequence) = android.text.SpannableStringBuilder(heading).apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.84f), 0, length, 0)
                val accent = when (first.speakers.singleOrNull()?.rem(3)) {
                    1 -> androidx.appcompat.R.attr.colorPrimary
                    2 -> com.google.android.material.R.attr.colorTertiary
                    0 -> com.google.android.material.R.attr.colorSecondary
                    else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                }
                setSpan(android.text.style.ForegroundColorSpan(com.google.android.material.color.MaterialColors.getColor(row, accent)),
                    0, first.speaker.length, 0)
                append("\n")
                val base = length
                append(text)
                paragraph.forEachIndexed { i, line ->
                    val from = offsets[i]
                    val to = (from + line.text.length).coerceAtMost(text.length)
                    if (from < to) setSpan(line, base + from, base + to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (context.isNotBlank()) {
                    val at = length
                    append("\n").append(context)
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), at, length, 0)
                }
            }
            row.text = render(body)
            row.contentDescription = row.text.toString()
            val last = paragraph.last()
            if (last === animate && visible) {
                animate = null
                if (ValueAnimator.areAnimatorsEnabled() && !getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
                    val breaks = BreakIterator.getCharacterInstance().apply { setText(last.text) }
                    val ends = generateSequence(breaks.first()) { breaks.next().takeUnless { it == BreakIterator.DONE } }.toList()
                    row.tag = ValueAnimator.ofInt(0, ends.lastIndex).apply {
                        duration = (ends.lastIndex * 40L).coerceAtMost(2400L)
                        addUpdateListener { row.text = render(body.substring(0, lastStart + ends[it.animatedValue as Int])) }
                        start()
                    }
                }
            }
            return row
        }
    }
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }
}
