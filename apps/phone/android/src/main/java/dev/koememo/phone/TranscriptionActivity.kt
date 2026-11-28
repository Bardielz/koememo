package dev.koememo.phone

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.view.accessibility.AccessibilityManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

class TranscriptionActivity : Activity() {
    private lateinit var file: File
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var rows: LinearLayout
    private lateinit var player: RecordingPlayer
    @Volatile private var cancelled = false
    private var busy = false
    private var shownWindows = 0
    private var visible = false
    private var typing: ValueAnimator? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = File(intent.getStringExtra("path") ?: "").canonicalFile
        if (!file.isFile || !file.toPath().startsWith(File(filesDir, "audio").canonicalFile.toPath()) ||
            file.extension !in setOf("aac", "ogg")) { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        status = TextView(this).apply { text = file.name }
        layout.addView(status)
        action = Button(this).apply { setOnClickListener {
            if (busy) { cancelled = true; isEnabled = false }
            else if (AsrModel.directory(this@TranscriptionActivity).isDirectory) start()
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        } }
        layout.addView(action)
        player = RecordingPlayer(this)
        layout.addView(player)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows) }
        layout.addView(scroll)
        rows.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (typing?.isRunning == true && bottom > oldBottom && scroll.scrollY >= oldBottom - scroll.height) {
                scroll.scrollTo(0, bottom)
            }
        }
        setContentView(layout)
        ready()
        worker.execute {
            try {
                val transcript = LocalTranscription(applicationContext, file)
                ui { display(transcript); if (transcript.data.optBoolean("complete")) action.isEnabled = false }
            } catch (e: Exception) { ui { status.text = "读取进度失败：" + e.message } }
        }
    }
    private fun ready() {
        busy = false
        action.isEnabled = true
        action.text = if (AsrModel.directory(this).isDirectory) "开始／继续本地转写" else "选择 SenseVoice INT8 模型目录"
    }
    private fun start() {
        cancelled = false; busy = true; action.text = "暂停转写"
        status.text = "正在本机加载模型…"
        worker.execute {
            try {
                val transcript = LocalTranscription(applicationContext, file)
                try { transcript.run({ cancelled }) { window -> ui {
                    if (window.count == shownWindows + 1) {
                        addWindow(window.startMs.toInt(), window.text, animate = visible)
                    }
                    status.text = "已保存 ${window.count} 个时间区间"
                } } }
                catch (_: CancellationException) { /* Committed windows are resumable. */ }
                ui { ready(); display(transcript); action.isEnabled = !transcript.data.optBoolean("complete") }
            } catch (e: Exception) { ui { ready(); status.text = "转写失败：" + e.message } }
            catch (e: LinkageError) { ui { ready(); status.text = "此设备无法加载识别运行库" } }
        }
    }
    private fun display(transcript: LocalTranscription) {
        val windows = transcript.data.getJSONArray("windows")
        for (i in shownWindows until windows.length()) {
            val w = windows.getJSONObject(i)
            addWindow(w.getInt("start_ms"), w.getString("text"), animate = false)
        }
        status.text = when {
            transcript.data.optBoolean("complete") -> if (rows.childCount == 0) "已完成，未识别出文字" else "已完成，点文字回听"
            windows.length() > 0 -> "已保存 ${windows.length()} 个区间，可继续"
            else -> file.name
        }
    }
    private fun addWindow(start: Int, text: String, animate: Boolean) {
        shownWindows++
        if (text.isBlank()) return
        typing?.end()
        val prefix = "%d:%02d　".format(start / 60000, start / 1000 % 60)
        val label = prefix + text
        val row = Button(this).apply {
            this.text = label
            contentDescription = label
            setOnClickListener { player.play(file, start) }
        }
        rows.addView(row)
        val accessibility = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || accessibility.isTouchExplorationEnabled) return

        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val boundaries = mutableListOf(iterator.first())
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            boundaries.add(end)
            end = iterator.next()
        }
        row.text = prefix
        var displayed = 0
        typing = ValueAnimator.ofInt(0, boundaries.lastIndex).apply {
            duration = (boundaries.lastIndex * 40L).coerceAtMost(2400L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val count = it.animatedValue as Int
                if (count != displayed) {
                    row.text = label.substring(0, prefix.length + boundaries[count])
                    displayed = count
                }
            }
            start()
        }
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 1 || result != RESULT_OK) return
        val tree = data?.data ?: return
        action.isEnabled = false; status.text = "正在校验并导入模型…"
        worker.execute {
            try { AsrModel.import(applicationContext, tree); ui { ready(); status.text = "模型已就绪" } }
            catch (e: Exception) { ui { ready(); status.text = "导入失败：" + e.message } }
        }
    }
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; typing?.end(); typing = null; super.onStop() }
    override fun onDestroy() { cancelled = true; if (::player.isInitialized) player.release(); super.onDestroy() }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
