package dev.watchmemory.recording.presentation.settings

import dev.watchmemory.recording.presentation.RotaryScrollView
import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.recording.application.RecordingSettings
import dev.watchmemory.recording.domain.*
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

open class SettingsActivity : Activity() {
    private lateinit var settings: RecordingSettings
    private val graph by lazy { AppGraph.get(this) }
    private var scroll: ScrollView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = graph.settings
        render()
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val position = scroll?.scrollY ?: 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(28), dp(30), dp(40))
        }
        scroll = RotaryScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll?.post { scroll?.scrollTo(0, position) }
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            column.addView(this)
        }
        label("设置")
        val toggle = Switch(this).apply {
            text = "实验功能"; textSize = 15f
            isChecked = settings.experimentsEnabled
            isEnabled = !graph.state.busy
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(if (graph.state.busy) "请先暂停录音再修改设置" else "默认关闭。关闭后使用标准录音；修改在下次开始或继续时生效。")
        val sources = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            visibility = if (settings.experimentsEnabled) View.VISIBLE else View.GONE
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        RecordingSource.entries.forEach { source ->
            val supported = source != RecordingSource.UNPROCESSED || settings.rawSupported
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = source.name + if (supported) "" else "（系统未声明支持）"
                textSize = 11f
                isEnabled = !graph.state.busy && supported
                sources.addView(this)
                isChecked = source.name == settings.selectedSource
            }
            radio.setOnClickListener { change { settings.setSource(source) } }
        }
        if (settings.experimentsEnabled) {
            column.addView(Switch(this).apply {
                text = "性能与耗电统计"; textSize = 14f
                isChecked = settings.selectedDiagnostics
                isEnabled = !graph.state.busy
                setOnCheckedChangeListener { _, value -> change { settings.setDiagnostics(value) } }
            })
            label("采集录音性能与同步耗电统计。下次录音或同步尝试生效。")
            label("录音后端")
            val backends = RadioGroup(this).apply { column.addView(this) }
            val selected = settings.requiredBackend
            val available = graph.captureBackends.availableIds(settings.configuration())
            (listOf(null) + available + listOfNotNull(selected)).distinct().forEach { id ->
                val option = RadioButton(this).apply {
                    this.id = View.generateViewId()
                    text = id ?: "自动选择"
                    isEnabled = !graph.state.busy && (id == null || id in available)
                    backends.addView(this)
                    isChecked = selected == id
                }
                option.setOnClickListener { change { settings.setBackend(id) } }
            }
            label("采样率")
            val rates = RadioGroup(this).apply { column.addView(this) }
            ExperimentPolicy.sampleRates.forEach { rate ->
                val radio = RadioButton(this).apply {
                    id = View.generateViewId(); text = "${rate / 1000} kHz"
                    isEnabled = !graph.state.busy
                    rates.addView(this)
                    isChecked = rate == settings.selectedSampleRate
                }
                radio.setOnClickListener { change { settings.setSampleRate(rate) } }
            }
            label("编码格式")
            val encodings = RadioGroup(this).apply { column.addView(this) }
            RecordingEncoding.entries.forEach { encoding ->
                val radio = RadioButton(this).apply {
                    id = View.generateViewId(); text = if (encoding == RecordingEncoding.AAC) "AAC-LC (.aac)" else "Opus (.ogg)"
                    isEnabled = !graph.state.busy; encodings.addView(this)
                    isChecked = encoding == settings.selectedEncoding
                }
                radio.setOnClickListener { change { settings.setEncoding(encoding) } }
            }
            label("目标码率")
            val bitrates = RadioGroup(this).apply { column.addView(this) }
            settings.selectedEncoding.rates.forEach { bitrate ->
                val radio = RadioButton(this).apply {
                    id = View.generateViewId(); text = "${bitrate / 1000} kbps"
                    isEnabled = !graph.state.busy; bitrates.addView(this)
                    isChecked = bitrate == settings.selectedBitrate
                }
                radio.setOnClickListener { change { settings.setBitrate(bitrate) } }
            }
            label("关闭实验功能后使用 MIC / 16 kHz / AAC 48 kbps。开始时检查配置，不支持会报错。Opus 的播放时间基准为 48 kHz，与采集采样率分别记录。")
        }
        if (settings.experimentsEnabled) label("实验音源每次只启用一种。所有实验功能均受上方总开关控制。")
        column.addView(Button(this).apply { text = "查看同步统计"; setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, SyncHistoryActivity::class.java)) } })
        toggle.setOnCheckedChangeListener { _, enabled -> change { settings.setExperiments(enabled) } }
        column.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
    }
    private fun change(action: () -> Unit) {
        try { action() } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        render()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
