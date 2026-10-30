package dev.watchmemory

import java.util.Locale

object UiText {
    fun deviceStats(battery: Int, freeBytes: Long): String =
        String.format(Locale.getDefault(), "电量 %d%% · 剩余 %.0f MB\n", battery, freeBytes / 1_000_000.0)
}
