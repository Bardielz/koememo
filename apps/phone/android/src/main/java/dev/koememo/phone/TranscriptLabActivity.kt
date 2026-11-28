package dev.koememo.phone

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.koememo.sync.sha256
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class TranscriptLabActivity : AppCompatActivity() {
    private lateinit var directory: File
    private lateinit var engine: AsrChoice
    private lateinit var status: TextView
    private lateinit var form: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var player: RecordingPlayer
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var worker: TranscriptWorker? = null
    @Volatile private var closed = false
    private lateinit var session: String
    private var batches = emptyList<List<TranscriptLine>>()
    private var selection = 0
    private var pending: TranscriptModel? = null
    private var busy = false
    private val backendSettings by lazy { getSharedPreferences("transcript-inference", MODE_PRIVATE) }
    private val preferredBackend get() = if (TranscriptBackend.verifiedSoc() && backendSettings.getBoolean("prefer_htp", true))
        TranscriptBackend.HTP else TranscriptBackend.CPU

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        directory = File(intent.getStringExtra("directory").orEmpty()).canonicalFile
        val root = File(filesDir, "audio").canonicalFile.toPath()
        if (!PhoneExperiments.enabled(this) || !directory.isDirectory ||
            directory.toPath() == root || !directory.toPath().startsWith(root)) { finish(); return }
        session = root.relativize(directory.toPath()).toString()
        engine = AsrChoice.from(intent.getStringExtra("engine"))
        selection = state?.getInt("selection") ?: 0
        pending = state?.getString("pending")?.let(TranscriptModel::valueOf)
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        status = TextView(this)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        player = RecordingPlayer(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom); insets
            }
            addView(navigationBar("文字稿实验", back = true))
            addView(ScrollView(context).apply { addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; addView(form); addView(status); addView(results)
            }) }, LinearLayout.LayoutParams(-1,0,1f))
            addView(player)
        }
        setContentView(page)
        work("正在读取已保存的文字稿") {
            val store = SpeakerStore(this, directory, SpeakerMethod.forFamily(engine.family))
            val analysis = store.analysis(); val names = store.names()
            val windows = AudioSession.load(directory).clips.filterNot { it.hashMismatch }.flatMap { clip ->
                val data = LocalTranscription(this, clip, engine).snapshot()
                transcriptLines(this, clip, engine, analysis, names, data).filter { it.text.isNotBlank() }
            }
            val groups = mutableListOf<MutableList<TranscriptLine>>()
            var chars = 0
            for (line in windows) {
                val prev = groups.lastOrNull()
                if (prev == null || prev.last().clip.file != line.clip.file || prev.size >= 8 || chars + line.text.length > 1500) {
                    groups.add(mutableListOf()); chars = 0
                }
                groups.last().add(line); chars += line.text.length
            }
            ui { batches = groups; selection = selection.coerceIn(0, (groups.size - 1).coerceAtLeast(0)); showSaved() }
            if (groups.isEmpty()) "请先转写这次录音" else "${windows.size} 个已保存的文字窗口"
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putInt("selection", selection); out.putString("pending", pending?.name)
    }
    override fun onStop() { if (::player.isInitialized) player.suspendUpdates(); super.onStop() }
    override fun onStart() { super.onStart(); if (::player.isInitialized) player.resumeUpdates() }
    override fun onDestroy() {
        closed = true
        worker?.close()
        io.shutdownNow()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
    private fun ui(action: () -> Unit) = runOnUiThread { if (!closed && !isFinishing) action() }
    private fun work(message: String, action: () -> String) {
        if (busy) return
        busy = true; status.text = message; drawControls()
        io.execute {
            val outcome = runCatching { action() }.getOrElse {
                "实验未完成 · " + (it.localizedMessage ?: it.javaClass.simpleName)
            }
            ui { busy = false; status.text = outcome; drawControls() }
        }
    }
    private fun button(text: String, enabled: Boolean = true, action: () -> Unit) {
        form.addView(MaterialButton(this).apply { this.text = text; isEnabled = enabled && !busy; setOnClickListener { action() } })
    }
    private fun drawControls() {
        form.removeAllViews()
        for (model in TranscriptModel.entries.filterNot { it.file(this).isFile }) button("导入 ${model.title}") {
            pending = model; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        }
        if (TranscriptBackend.verifiedSoc()) {
            button("推理处理器　" + if (preferredBackend == TranscriptBackend.HTP) "优先 Hexagon NPU" else "CPU") {
                MaterialAlertDialogBuilder(this).setTitle("纪要和索引处理器")
                    .setSingleChoiceItems(arrayOf("优先 Hexagon NPU", "CPU"), if (preferredBackend == TranscriptBackend.HTP) 0 else 1) { dialog, i ->
                        dialog.dismiss()
                        backendSettings.edit().putBoolean("prefer_htp", i == 0).apply()
                        drawControls(); showSaved()
                    }.show()
            }
        }
        if (batches.isEmpty()) return
        button("对照范围　${selection + 1} / ${batches.size}") {
            MaterialAlertDialogBuilder(this).setTitle("同一份文字输入")
                .setSingleChoiceItems(batches.mapIndexed { i, lines -> "${i + 1}　${lines.first().text.take(36)}" }.toTypedArray(), selection) { dialog, i ->
                    dialog.dismiss(); selection = i; drawControls(); showSaved()
                }.show()
        }
        val readers = listOf(TranscriptModel.MEETING, TranscriptModel.BASE)
        button("比较两种纪要", readers.all { it.file(this).isFile }) { compare(readers) }
        for (model in TranscriptModel.entries.filter { it.embedding }) {
            button("建立整场 ${model.title} 文字索引", model.file(this).isFile) {
                work("建立索引中") {
                    val lines = batches.flatten()
                    val backend = withWorker(model) { w ->
                        TranscriptIndex(this).use { index ->
                            index.build(session, engine.key, model, lines, w::run, { done ->
                                check(!closed)
                                if (done % 10 == 0) ui { status.text = "${w.backend.label} 索引 $done / ${lines.size}" }
                            }, w.backend)
                        }
                    }
                    "${model.title} 索引已保存 · ${backend.label} · ${lines.size} 个原音位置"
                }
            }
        }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val model = pending ?: return
        pending = null
        val tree = data?.data ?: return
        if (request == 1 && result == RESULT_OK) work("校验 ${model.title} 权重") {
            model.install(this, tree); "${model.title} 已就绪"
        }
    }
    private fun withWorker(model: TranscriptModel, call: (TranscriptWorker) -> Unit): TranscriptBackend {
        fun execute(backend: TranscriptBackend): TranscriptBackend {
            TranscriptWorker(this, model, backend).use {
                worker = it
                try { check(!closed); it.initialize(); call(it) } finally { worker = null }
            }
            return backend
        }
        val preferred = preferredBackend
        return try { execute(preferred) } catch (e: TranscriptInferenceFailure) {
            if (preferred == TranscriptBackend.CPU || closed) throw e
            android.util.Log.w("KoeMemoGemma", "${model.name} HTP failed; retrying on CPU", e)
            ui { status.text = "${model.title} · NPU 不可用，正在用 CPU 重试" }
            execute(TranscriptBackend.CPU)
        }
    }
    private fun noteFile(model: TranscriptModel, lines: List<TranscriptLine>, preference: TranscriptBackend = preferredBackend): AtomicFile {
        val fingerprint = stableDigest(org.json.JSONArray(listOf("notes-v1", session, engine.key, model.sha,
            lines.first().clip.file.name, lines.first().clip.hash) + lines.map { listOf(it.start, it.end, it.speaker, it.text) }).toString())
        val suffix = if (preference == TranscriptBackend.CPU) "" else "-htp-preferred"
        return AtomicFile(File(filesDir, "meeting-notes/$fingerprint$suffix.json"))
    }
    private fun compare(readers: List<TranscriptModel>) {
        val lines = batches[selection]
        work("正在对照") {
            val system = File(TranscriptModel.MEETING.file(this).parentFile, "system_prompt.txt").readText()
            val prompt = meetingPrompt(system, lines)
            for (model in readers) {
                check(!closed)
                ui { status.text = "正在生成 · ${model.title}" }
                val start = SystemClock.elapsedRealtime()
                withWorker(model) { w ->
                    val output = w.run(prompt).decodeToString()
                    writeJson(noteFile(model, lines), JSONObject().put("output",output)
                        .put("backend", w.backend.name).put("elapsed_ms",SystemClock.elapsedRealtime() - start))
                }
                ui { showSaved() }
            }
            "对照完成 · 同一文字范围、提示词和贪心解码设置"
        }
    }
    private fun showSaved() {
        results.removeAllViews()
        val lines = batches.getOrNull(selection) ?: return
        for (model in listOf(TranscriptModel.MEETING, TranscriptModel.BASE)) {
            val data = readJson(noteFile(model, lines)) {
                readJson(noteFile(model, lines, TranscriptBackend.CPU), ::JSONObject)
            }
            if (!data.has("output")) continue
            val output = data.getString("output")
            results.addView(TextView(this).apply {
                val backend = TranscriptBackend.valueOf(data.optString("backend", TranscriptBackend.CPU.name))
                text = "${model.title} · ${backend.label} · ${data.getLong("elapsed_ms") / 1000.0} 秒"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            val notes = runCatching { meetingNotes(output, lines) }
            notes.onFailure { error -> results.addView(TextView(this).apply { text = "${error.message}\n$output" }) }
                .onSuccess { items ->
                    if (items.isEmpty()) results.addView(TextView(this).apply { text = "该模型未提取到要点" })
                    for (note in items) results.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                        text = "${sourceTime(note.start)}　${note.category}　${note.body}"
                        setOnClickListener {
                            val line = lines.first { it.start == note.start }
                            work("校验原音") {
                                check(line.clip.file.sha256() == line.clip.hash) { "原音校验失败" }
                                ui { player.play(line.clip.file, line.start, line.clip.span, "纪要引用原音") }; "已定位到原话"
                            }
                        }
                    })
                }
        }
    }
}
