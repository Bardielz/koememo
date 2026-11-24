package dev.koememo.phone

import dev.koememo.sync.Segment
import dev.koememo.sync.sha256
import java.io.File

internal class PendingSegment(val index: Int, val item: Segment, nodeDirectory: File, val acknowledged: Boolean) {
    private val directory = File(nodeDirectory, item.session)
    val target = File(directory, item.name)
    val part = File(directory, "${item.name}.${item.hash}.part")

    // Snapshot once. Sorting must not stat files inside each comparator invocation.
    val priority = when {
        part.length() in 1..item.size -> 0
        target.length() != item.size -> 1
        else -> 2
    }

    fun verified() = target.isFile && target.length() == item.size && target.sha256() == item.hash
}
