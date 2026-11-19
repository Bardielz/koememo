package dev.koememo.phone

import android.app.Activity
import android.os.Bundle
import android.media.MediaPlayer
import android.widget.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import dev.koememo.sync.*
import java.io.*

class LibraryActivity : Activity() {
    companion object { private val transferring = java.util.concurrent.atomic.AtomicBoolean(false) }
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var exporting: File? = null
    private lateinit var sync: Button
    private var player: MediaPlayer? = null
    @Volatile private var active: ChannelClient.Channel? = null
    @Volatile private var cancelled = false
    private val root get() = File(filesDir,"audio")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this); sync = Button(this).apply { text = "从手表同步"; setOnClickListener { startSync() } }
        layout.addView(sync); layout.addView(Button(this).apply { text = "暂停同步"; setOnClickListener { cancel() } })
        layout.addView(status)
        exporting = state?.getString("exportFile")?.let(::File)
        list = ListView(this)
        layout.addView(list); setContentView(layout); refresh()
    }
    private fun cancel() { cancelled = true; active?.let { Wearable.getChannelClient(this).close(it) } }
    override fun onDestroy() { cancel(); player?.release(); super.onDestroy() }
    private fun refresh() {
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("aac","ogg") }.sortedByDescending { it.path }.toList()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files.map { it.parentFile!!.name + "/" + it.name })
        list.setOnItemClickListener { _, _, index, _ ->
            val file = files[index]
            android.app.AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("播放", "导出")) { _, choice ->
                if (choice == 0) {
                    player?.release()
                    player = MediaPlayer()
                    runCatching { player!!.apply { setDataSource(file.path); prepare(); start() } }
                        .onFailure { status.text = "播放失败 " + it.message }
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
    private fun startSync() {
        if (!transferring.compareAndSet(false,true)) { status.text = "同步进行中"; return }
        sync.isEnabled = false; cancelled = false; status.text = "正在查找手表"
        Thread {
            val result = runCatching { receive() }.fold({ "同步完成，已校验 ${it} 段" }, { if (cancelled) "已暂停，可继续同步" else "同步失败，可重试：" + (it.message ?: it.javaClass.simpleName) })
            transferring.set(false)
            runOnUiThread { if (!isDestroyed) { status.text = result; sync.isEnabled = true; refresh() } }
        }.start()
    }
    private fun receive(): Int {
        val nodes = Tasks.await(Wearable.getCapabilityClient(this).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)).nodes
        val watch = nodes.singleOrNull() ?: error(if (nodes.isEmpty()) "未发现安装 KoeMemo 的手表" else "请仅连接一台手表")
        val client = Wearable.getChannelClient(this)
        val channel = Tasks.await(client.openChannel(watch.id, CHANNEL))
        active = channel
        try {
            check(!cancelled)
            val input = DataInputStream(Tasks.await(client.getInputStream(channel)).buffered())
            val output = DataOutputStream(Tasks.await(client.getOutputStream(channel)).buffered())
            val count = input.readInt(); require(count in 0..100000)
            val entries = List(count) { Segment.read(input) }
            entries.forEachIndexed { index, item ->
                check(!cancelled)
                val dir = File(File(root,watch.id.toByteArray().joinToString("") { "%02x".format(it) }),item.session)
                check(dir.isDirectory || dir.mkdirs())
                val target = File(dir,item.name)
                val done = target.length() == item.size && target.sha256() == item.hash
                val part = File(dir,item.name + "." + item.hash + ".part")
                if (part.length() > item.size) check(part.delete())
                val offset = if (done) item.size else part.length()
                runOnUiThread { status.text = "正在同步 ${index+1}/$count" }
                output.writeInt(index); output.writeLong(offset); output.flush()
                if (!done) {
                    check(dir.usableSpace > item.size-offset) { "手机存储空间不足" }
                    FileOutputStream(part,true).use { copyExactly(input,it,item.size-offset); it.fd.sync() }
                    if (part.sha256() != item.hash) { check(part.delete()); error("校验失败，请重试") }
                    android.system.Os.rename(part.path,target.path)
                }
                output.writeUTF(item.hash); output.flush()
            }
            output.writeInt(-1); output.flush()
            check(input.readBoolean()) { "手表未确认完成，请重试" }
            return count
        } finally { client.close(channel); active = null }
    }
}
