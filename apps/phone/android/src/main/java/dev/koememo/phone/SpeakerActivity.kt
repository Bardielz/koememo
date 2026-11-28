package dev.koememo.phone

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.os.Bundle
import android.widget.*
import com.google.android.material.button.MaterialButton
import dev.koememo.sync.sha256
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SpeakerActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val stop = AtomicBoolean()
    private lateinit var directory: File
    private lateinit var store: SpeakerStore
    private lateinit var action: Button
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var player: RecordingPlayer
    private var analyzing = false
    private data class Example(val id: Int, val clip: SessionClip, val offset: Int, val name: String)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        directory = File(intent.getStringExtra("directory").orEmpty()).canonicalFile
        if (!directory.isDirectory || !directory.toPath().startsWith(File(filesDir, "audio").canonicalFile.toPath())) { finish(); return }
        store = SpeakerStore(this, directory)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        layout.addView(navigationBar("本次录音的发言人", true).apply { subtitle = sessionTitle(directory) })
        action = MaterialButton(this).apply { isEnabled = false; setOnClickListener {
            when {
                analyzing -> { stop.set(true); isEnabled = false; status.text = "正在暂停…" }
                SpeakerModel.directory(this@SpeakerActivity).isDirectory -> analyze()
                else -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
            }
        } }
        layout.addView(action)
        status = TextView(this).apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
        layout.addView(status)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        player = RecordingPlayer(this)
        layout.addView(player)
        setContentView(layout)
        io.execute { refresh() }
    }

    private fun refresh(note: String = "") {
        try {
            val data = store.analysis()
            val names = store.names()
            val clips = AudioSession.load(directory).clips
            val examples = linkedMapOf<Int, Example>()
            var done = 0
            for (clip in clips) {
                val turns = SpeakerStore.turns(data, clip) ?: continue
                done++
                for (turn in turns) if (turn.id > 0 && turn.id !in examples) {
                    examples[turn.id] = Example(turn.id, clip, turn.start, SpeakerStore.display(turn.id, names))
                }
            }
            val model = SpeakerModel.directory(this).isDirectory
            ui {
                status.text = listOf(note, when {
                    clips.isEmpty() -> "等待音频同步"
                    done == clips.size && examples.isEmpty() -> "未能区分出发言人"
                    done > 0 -> "已分析 ${100L * done / clips.size}% · 区分出 ${examples.size} 位发言人"
                    model -> "仅分析本次录音，结果保存在手机。退出此页会暂停，已完成的音频可以接着用。"
                    else -> "选择包含分人模型与声音特征模型的文件夹。"
                }).filter(String::isNotBlank).joinToString("\n")
                action.text = when {
                    done == clips.size && done > 0 -> "已完成分析"
                    !model -> "导入发言人模型"
                    done > 0 -> "继续区分发言人"
                    else -> "区分本次发言人"
                }
                action.isEnabled = clips.isNotEmpty() && done < clips.size
                rows.removeAllViews()
                examples.values.sortedBy { it.id }.forEach { addExample(it) }
            }
        } catch (e: Exception) { ui { status.text = "读取发言人失败 · ${e.localizedMessage}" } }
    }

    private fun analyze() {
        if (!busy.compareAndSet(false, true)) { status.text = "另一项发言人分析正在运行"; return }
        stop.set(false)
        analyzing = true
        action.text = "暂停分析"
        status.text = "正在准备模型…"
        io.execute {
            val message = try {
                val data = store.analysis()
                val clips = AudioSession.load(directory).clips
                var firstError: String? = null
                SpeakerAnalysis(SpeakerModel.directory(this)).use { engine ->
                    for ((i, clip) in clips.withIndex()) {
                        if (stop.get()) throw CancellationException()
                        if (SpeakerStore.turns(data, clip) != null) continue
                        ui { status.text = "正在区分发言人 · ${100L * i / clips.size}%" }
                        val profiles = SpeakerStore.profiles(data)
                        val turns = try { engine.analyze(clip, profiles, stop::get) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (firstError == null) firstError = e.localizedMessage ?: "原音无法分析"
                                continue
                            }
                        if (stop.get()) throw CancellationException()
                        store.save(data, clip, turns, profiles)
                    }
                }
                firstError?.let { "部分原音尚未分析 · $it" }.orEmpty()
            } catch (_: CancellationException) { "已暂停" }
            catch (_: InterruptedException) { "已暂停" }
            catch (e: Exception) { "分析中断 · ${e.localizedMessage}" }
            catch (_: LinkageError) { "设备无法加载分人模型运行库" }
            finally { busy.set(false) }
            ui { analyzing = false }
            if (!stop.get() || !isDestroyed) refresh(message)
        }
    }

    private fun addExample(example: Example) {
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = example.name; textSize = 17f; setPadding(dp(16), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { text = "回听"; contentDescription = "回听${example.name}"; setOnClickListener {
            io.execute {
                val valid = runCatching { example.clip.file.sha256() == example.clip.hash }.getOrDefault(false)
                ui { if (valid) player.play(example.clip.file, example.offset, example.clip.span, example.name)
                    else status.text = "原音已更换，请重新分析" }
            }
        } })
        row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { text = "命名"; contentDescription = "为${example.name}命名"; setOnClickListener {
            val input = EditText(this@SpeakerActivity).apply { hint = "仅用于本次录音"; setSingleLine()
                if (example.name != "发言人 ${example.id}") setText(example.name) }
            MaterialAlertDialogBuilder(this@SpeakerActivity).setTitle("发言人 ${example.id} 的名字").setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val name = input.text.toString()
                    io.execute { try { store.rename(example.id, name); refresh() }
                        catch (e: Exception) { ui { status.text = "保存失败 · ${e.localizedMessage}" } } }
                }.setNegativeButton("取消", null).show()
        } })
        rows.addView(row)
    }

    @Deprecated("Uses platform document picker")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 1 || result != RESULT_OK) return
        val tree = data?.data ?: return
        action.isEnabled = false
        status.text = "正在校验模型…"
        io.execute {
            val message = runCatching { importModel(this, tree, SpeakerModel.ID, SpeakerModel.files); "模型已就绪" }
                .getOrElse { "导入失败 · ${it.localizedMessage}" }
            refresh(message)
        }
    }
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    override fun onDestroy() {
        stop.set(true)
        io.shutdown() // Allow the analysis finally block to relinquish its process-wide lease.
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
    companion object { private val busy = AtomicBoolean() }
}
