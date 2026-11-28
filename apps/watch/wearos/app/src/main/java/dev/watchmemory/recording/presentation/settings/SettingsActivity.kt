package dev.watchmemory.recording.presentation.settings

import dev.watchmemory.recording.presentation.RotaryScrollView
import dev.watchmemory.bootstrap.AppGraph
import dev.watchmemory.recording.application.RecordingSettings
import dev.watchmemory.recording.domain.*
import dev.watchmemory.recording.infrastructure.android.RecordingSensors
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

open class SettingsActivity : Activity() {
    private lateinit var settings: RecordingSettings
    private val graph by lazy { AppGraph.get(this) }
    private var probing = false
    private var scroll: ScrollView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = graph.settings
    }
    override fun onResume() { super.onResume(); render() }
    // Rebuilding dependent choices must not send a gesture user back to the first control.
    private val controls = mutableMapOf<String, View>()

    private fun render() {
        val accessibilityFocus = controls.entries.firstOrNull { it.value.isAccessibilityFocused }?.key
        val inputFocus = controls.entries.firstOrNull { it.value.isFocused }?.key
        controls.clear()
        val position = scroll?.scrollY ?: 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(28), dp(30), dp(40))
        }
        scroll = RotaryScrollView(this).apply { addView(column) }
        setContentView(scroll)
        val page = scroll ?: return
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            column.addView(this)
        }
        label("设置")
        column.addView(Switch(this).apply {
            controls["noise_suppression"] = this
            text = "系统降噪"
            isChecked = settings.noiseSuppression
            isEnabled = !graph.state.busy
            setOnCheckedChangeListener { _, enabled -> change { settings.setNoiseSuppression(enabled) } }
        })
        label("下次录音生效。设备不支持时照常录音。原始音源保留原始输入。")
        val toggle = Switch(this).apply {
            controls["experiments"] = this
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
                controls["source:$source"] = this
                sources.addView(this)
                isChecked = source.name == settings.selectedSource
            }
            radio.setOnClickListener { change { settings.setSource(source) } }
        }
        if (settings.experimentsEnabled) {
            column.addView(Switch(this).apply {
                controls["quietGate"] = this
                text = "静音时暂停写入"
                isChecked = settings.selectedQuietGate
                isEnabled = !graph.state.busy
                setOnCheckedChangeListener { _, value -> change { settings.setQuietGate(value) } }
            })
            label("连续安静约 10 秒后省略静音，麦克风继续监听。声音恢复时保留前 1 秒，播放时长会缩短。")
            column.addView(Switch(this).apply {
                controls["diagnostics"] = this
                text = "性能与耗电统计"; textSize = 14f
                isChecked = settings.selectedDiagnostics
                isEnabled = !graph.state.busy
                setOnCheckedChangeListener { _, value -> change { settings.setDiagnostics(value) } }
            })
            label("采集录音性能与同步耗电统计。下次录音或同步尝试生效。")
            fun optimization(key: String, title: String, selected: Boolean, available: Boolean, save: (Boolean) -> Unit) {
                column.addView(Switch(this).apply {
                    controls[key] = this
                    text = title
                    isChecked = selected
                    isEnabled = !graph.state.busy && available
                    setOnCheckedChangeListener { _, value -> change { save(value) } }
                })
            }
            optimization("direct_pcm", "PCM 直读编码器", settings.selectedDirectPcm, true, settings::setDirectPcm)
            val hasHeart = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_HEART_RATE)
            optimization("sensor_timeline", "录音时记录运动和心率", settings.selectedSensorTimeline, true) { enabled ->
                settings.setSensorTimeline(enabled)
                if (enabled && hasHeart && checkSelfPermission(RecordingSensors.heartPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(RecordingSensors.heartPermission), 42)
                }
            }
            label(when {
                !hasHeart -> "此表未声明心率传感器，可记录三轴加速度。"
                checkSelfPermission(RecordingSensors.heartPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    "心率需要授权。仅记录运动也可以继续录音。"
                else -> "开启后随录音记录三轴加速度和心率。戴稳手表有助于获得有效心率。"
            })
            label("与手机同步已保存的录音时，同时分享每 15 秒的活动和心率摘要。原始样本保存在手表。")
            optimization("reuse_ogg", "复用 Ogg 缓冲（Opus）", settings.selectedReuseOgg,
                settings.selectedEncoding == RecordingEncoding.OPUS, settings::setReuseOgg)
            column.addView(Button(this).apply {
                text = "探测三星性能 SDK"
                isEnabled = !probing && PerformanceProbe.result == null && !graph.state.busy
                setOnClickListener {
                    probing = true
                    isEnabled = false
                    text = "正在探测…"
                    Thread({
                        PerformanceProbe.run(applicationContext)
                        runOnUiThread {
                            probing = false
                            if (!isDestroyed && !isFinishing) render()
                        }
                    }, "samsung-performance-probe").start()
                }
            })
            PerformanceProbe.result?.let { label(it) }
            label("录音后端")
            val backends = RadioGroup(this).apply { column.addView(this) }
            val selected = settings.requiredBackend
            val available = graph.captureBackends.availableIds(settings.configuration())
            (listOf(null) + available + listOfNotNull(selected)).distinct().forEach { id ->
                val option = RadioButton(this).apply {
                    this.id = View.generateViewId()
                    text = id ?: "自动选择"
                    isEnabled = !graph.state.busy && (id == null || id in available)
                    controls["backend:${id ?: ""}"] = this
                    backends.addView(this)
                    isChecked = selected == id
                }
                option.setOnClickListener { change { settings.setBackend(id) } }
            }
            column.addView(Button(this).apply {
                controls["wear"] = this
                text = "佩戴触发对照"
                isEnabled = !graph.state.busy
                setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, WearSignalActivity::class.java)) }
            })
            label("采样率")
            val rates = RadioGroup(this).apply { column.addView(this) }
            ExperimentPolicy.sampleRates.forEach { rate ->
                val radio = RadioButton(this).apply {
                    id = View.generateViewId(); text = "${rate / 1000} kHz"
                    isEnabled = !graph.state.busy
                    controls["rate:$rate"] = this
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
                    controls["encoding:$encoding"] = this
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
                    controls["bitrate:$bitrate"] = this
                    isEnabled = !graph.state.busy; bitrates.addView(this)
                    isChecked = bitrate == settings.selectedBitrate
                }
                radio.setOnClickListener { change { settings.setBitrate(bitrate) } }
            }
            label("关闭实验功能后使用 MIC / 16 kHz / AAC 48 kbps。开始时检查配置，不支持会报错。Opus 的播放时间基准为 48 kHz，与采集采样率分别记录。")
        }
        if (settings.experimentsEnabled) label("实验音源每次只启用一种。所有实验功能均受上方总开关控制。")
        column.addView(Button(this).apply { controls["history"] = this; text = "查看同步统计"; setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, SyncHistoryActivity::class.java)) } })
        toggle.setOnCheckedChangeListener { _, enabled -> change { settings.setExperiments(enabled) } }
        column.addView(Button(this).apply { controls["back"] = this; text = "返回"; setOnClickListener { finish() } })
        page.post {
            if (scroll !== page || isFinishing) return@post
            page.scrollTo(0, position)
            controls[inputFocus]?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
            controls[accessibilityFocus]?.takeIf { it.isShown && it.isEnabled }
                ?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }
    private fun change(action: () -> Unit) {
        try { action() } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        render()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
