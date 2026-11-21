package dev.watchmemory.recording.presentation.settings

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.koememo.telemetry.readHistory
import dev.watchmemory.recording.presentation.RotaryScrollView
import java.io.File
import kotlin.math.roundToInt

class SyncHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (32 * resources.displayMetrics.density).roundToInt()
            setPadding(inset, inset, inset, inset)
        }
        content.addView(TextView(this).apply { text = "最近同步统计"; gravity = Gravity.CENTER })
        val message = TextView(this).apply { text = "正在读取…" }
        content.addView(message)
        content.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(RotaryScrollView(this).apply { addView(content) })
        Thread {
            val report = runCatching { readHistory(File(filesDir, "diagnostics")) }
                .getOrElse { "读取失败：${it.localizedMessage}" }
            runOnUiThread { if (!isFinishing && !isDestroyed) message.text = report }
        }.start()
    }
}
