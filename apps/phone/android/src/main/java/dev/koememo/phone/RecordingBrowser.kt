package dev.koememo.phone

import android.app.Activity
import android.view.MenuItem
import android.view.View
import android.widget.Toolbar
import dev.koememo.sync.TimelineJournal
import dev.koememo.sync.readTimeline
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal fun Activity.navigationBar(heading: String, back: Boolean): Toolbar = Toolbar(this).apply {
    title = heading
    if (back) {
        setNavigationIcon(R.drawable.ic_back)
        navigationContentDescription = "返回"
        setNavigationOnClickListener { finish() }
    }
}

internal fun Toolbar.action(label: String, visibleOnBar: Boolean = false, run: () -> Unit): MenuItem =
    menu.add(label).apply {
        if (visibleOnBar) setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        setOnMenuItemClickListener { run(); true }
    }

internal fun View.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

private val sessionId = Regex("""(\d{8}-\d{6})-[0-9a-fA-F]{8}""")
private val dateInName = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
private val displayDate = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
private val searchDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
private val segmentName = Regex("""run-([a-zA-Z0-9-]+)-(\d+)\.(aac|ogg)""")

private fun startedAt(dir: File): LocalDateTime? = sessionId.matchEntire(dir.name)?.let {
    runCatching { LocalDateTime.parse(it.groupValues[1], dateInName) }.getOrNull()
}

internal fun sessionTitle(dir: File): String = startedAt(dir)?.format(displayDate) ?: dir.name
internal fun directoryTimeline(dir: File) = File(dir, TimelineJournal.FILE).let { file ->
    if (file.isFile) file.useLines { readTimeline(it) } else emptyList()
}

internal data class BrowserRow(val file: File, val title: String, val subtitle: String, val keywords: String,
    val number: Int? = null, val storedMs: Long = 0) {
    private val searchable = "$title $subtitle $keywords ${file.name}".lowercase(Locale.ROOT)
    fun matches(query: String): Boolean {
        val term = query.trim().lowercase(Locale.ROOT).replace('/', '-')
        if (number != null && term.length in 1..4 && term.all { it in '0'..'9' }) return number == term.toInt()
        return term.split(' ').all { it in searchable }
    }
}

internal fun sessionRow(dir: File, count: Int): BrowserRow {
    val start = startedAt(dir)
    return BrowserRow(dir, start?.format(displayDate) ?: dir.name, if (start == null) "$count 个本地片段" else "会话开始 · $count 个本地片段",
        start?.format(searchDate).orEmpty())
}

internal fun segmentRow(file: File, storedMs: Long, storedAt: String, recordedAt: String?): BrowserRow {
    val name = segmentName.matchEntire(file.name)
    val group = name?.groupValues?.get(1)
    val number = name?.groupValues?.get(2)?.toIntOrNull()
    val title = if (number == null) file.name else "片段 $number"
    val time = recordedAt?.let { "录于 $it" } ?: "存入 $storedAt"
    val detail = listOfNotNull(time, group?.let { "组 $it" }, file.extension.uppercase(Locale.ROOT)).joinToString("  ·  ")
    return BrowserRow(file, title, detail, storedAt, number, storedMs)
}

internal fun describeSession(target: File, session: AudioSession): String {
    val runs = session.runs
    val clock = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    val sync = session.catalog?.let {
        "手表清单（${clock.format(Date(it.observedMs))}）已同步 ${session.received}/${it.hashes.size} 段"
    } ?: "本机 ${session.clips.size} 段，手表清单总数未知"
    if (runs.isEmpty()) {
        val why = if (session.timelineError) "时间轴读取失败" else "没有时间轴"
        return "${target.name}\n$sync\n$why，录制起止、静音省略和缺口未知"
    }
    val start = clock.format(Date(runs.first().wallMs))
    val end = runs.last().endWallMs?.let { clock.format(Date(it)) } ?: "结束时刻未知"
    val unknown = session.uncoveredUs?.let(::audioDuration) ?: "未知"
    val local = "本机 ${session.clips.size} 段，时间轴与哈希匹配 ${session.mapped.size} 段"
    val coverage = "已匹配音频约 ${audioDuration(session.mappedUs)} · 已知省略 ${audioDuration(session.omittedUs)}"
    val missing = if (session.missing > 0) " · 快照中另有 ${session.missing} 段缺文件" else ""
    val errors = runs.count { it.endEvent == "error" }
    val mismatches = session.clips.count { it.hashMismatch }
    return listOf("$start 至 $end · ${runs.size} 轮", sync, local + missing, coverage,
        "采集轮次内未覆盖　$unknown" + (if (errors > 0) " · 录制错误 ${errors} 轮" else "") +
            (if (mismatches > 0) " · $mismatches 段校验失败" else "")).joinToString("\n")
}
internal fun duration(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
private fun audioDuration(us: Long) = if (us in 1..999_999) "不足 1 秒" else duration(us / 1_000_000)
