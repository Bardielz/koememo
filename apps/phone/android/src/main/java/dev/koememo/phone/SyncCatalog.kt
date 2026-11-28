package dev.koememo.phone

import dev.koememo.sync.Segment
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Last fully received watch manifest, published before any segment downloads begin. */
internal data class SyncCatalog(val observedMs: Long, val hashes: Map<String, String>) {
    companion object {
        const val FILE = "sync-catalog.json"

        fun save(directory: File, segments: List<Segment>, observedMs: Long) {
            check(directory.isDirectory || directory.mkdirs())
            val hashes = segments.associate { it.name to it.hash }
            require(hashes.size == segments.size)
            val json = JSONObject().put("observed_ms", observedMs).put("hashes", JSONObject(hashes))
            val temp = File.createTempFile("catalog-", ".tmp", directory)
            try {
                temp.writeText(json.toString())
                Files.move(temp.toPath(), File(directory, FILE).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }

        fun read(directory: File): SyncCatalog? {
            val file = File(directory, FILE)
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val hashes = json.getJSONObject("hashes")
            return SyncCatalog(json.getLong("observed_ms"), hashes.keys().asSequence().associateWith(hashes::getString))
        }
    }
}
