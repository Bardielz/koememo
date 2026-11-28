package dev.koememo.phone

import dev.koememo.sync.ContextWindow
import java.util.Locale

internal fun ContextWindow.description(): String {
    val motion = meanMotion?.let { String.format(Locale.getDefault(), "腕部活动 %.2f m/s²", it) }
        ?: "无运动样本"
    val heart = when {
        validHeartCount == 0 -> if (heartCount == 0) "未收到心率" else "无有效心率"
        minBpm == maxBpm -> "心率 ${formatBpm(minBpm!!)} BPM"
        else -> "心率 ${formatBpm(minBpm!!)}–${formatBpm(maxBpm!!)} BPM"
    }
    return "$motion　$heart\n有效心率 $validHeartCount/$heartCount 条，${seconds - heartSeconds}/${seconds} 个秒格无有效读数"
}

private fun formatBpm(bpm: Double): String = if (bpm == bpm.toInt().toDouble()) bpm.toInt().toString()
    else String.format(Locale.getDefault(), "%.1f", bpm)
