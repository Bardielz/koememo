package dev.koememo.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

internal data class SpeakerTurn(val start: Int, val end: Int, val id: Int)

/** Stable IDs are scoped to a recording. Zero means there was too little clean speech. */
internal class SpeakerProfiles(val voices: MutableList<FloatArray>) {
    fun assign(samples: Map<Int, FloatArray>): Map<Int, Int> {
        val normalized = samples.mapNotNull { (local, vector) ->
            val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            if (norm.isFinite() && norm > 0) local to FloatArray(vector.size) { vector[it] / norm } else null
        }.toMap()
        val result = mutableMapOf<Int, Int>()
        val used = mutableSetOf<Int>()
        // Two voices distinguished in one batch must keep distinct identities.
        val candidates = normalized.mapNotNull { (local, v) ->
            val ranked = voices.mapIndexedNotNull { i, ref ->
                if (v.size != ref.size) null else i to v.indices.sumOf { v[it].toDouble() * ref[it] }
            }.sortedByDescending { it.second }
            ranked.firstOrNull()?.takeIf { it.second >= 0.55 && it.second - (ranked.getOrNull(1)?.second ?: -1.0) >= 0.05 }
                ?.let { Triple(local, it.first, it.second) }
        }
        for ((local, ref) in candidates.sortedByDescending { it.third }) {
            if (used.add(ref)) result[local] = ref + 1
        }
        for ((local, v) in normalized) if (local !in result) {
            voices.add(v)
            result[local] = voices.size
        }
        return result
    }
}

internal class SpeakerStore(context: Context, directory: File, method: SpeakerMethod = SpeakerMethod.DEFAULT) {
    private val root = File(context.filesDir, "audio").canonicalFile.toPath()
    private val path = directory.canonicalFile.toPath().also { require(it != root && it.startsWith(root)) }
    private val folder = File(File(context.filesDir, method.storeRoot), root.relativize(path).toString())
    private val report = AtomicFile(File(folder, "analysis.json"))
    private val aliases = AtomicFile(File(folder, "names.json"))

    fun analysis() = readJson(report) { JSONObject().put("clips", JSONObject()).put("voices", JSONArray()) }
    fun names() = readJson(aliases, ::JSONObject)
    fun rename(id: Int, name: String) = synchronized(aliasLock) {
        require(id > 0)
        val names = names()
        if (name.isBlank()) names.remove(id.toString()) else names.put(id.toString(), name.trim().take(64))
        writeJson(aliases, names)
    }

    fun save(data: JSONObject, clip: SessionClip, turns: List<SpeakerTurn>, profiles: SpeakerProfiles) {
        val spans = JSONArray()
        turns.forEach { spans.put(JSONArray().put(it.start).put(it.end).put(it.id)) }
        data.getJSONObject("clips").put(clip.file.name, JSONObject().put("hash", clip.hash).put("turns", spans))
        val voices = JSONArray()
        for (v in profiles.voices) voices.put(JSONArray().apply { v.forEach { put(it.toDouble()) } })
        data.put("voices", voices)
        writeJson(report, data)
    }

    companion object {
        private val aliasLock = Any()
        fun display(id: Int, names: JSONObject): String = if (id == 0) "待确认"
            else names.optString(id.toString()).ifBlank { "发言人 $id" }
        fun profiles(data: JSONObject): SpeakerProfiles {
            val list = data.getJSONArray("voices")
            return SpeakerProfiles(MutableList(list.length()) { i ->
                val v = list.getJSONArray(i)
                FloatArray(v.length()) { v.getDouble(it).toFloat() }
            })
        }
        fun turns(data: JSONObject, clip: SessionClip): List<SpeakerTurn>? {
            val saved = data.getJSONObject("clips").optJSONObject(clip.file.name)
                ?.takeIf { !clip.hashMismatch && it.optString("hash") == clip.hash } ?: return null
            val list = saved.getJSONArray("turns")
            return List(list.length()) { i ->
                val t = list.getJSONArray(i)
                SpeakerTurn(t.getInt(0), t.getInt(1), t.getInt(2))
            }
        }
        fun label(turns: List<SpeakerTurn>, start: Int, end: Int, names: JSONObject): String {
            val ids = turns.filter { it.end > start && it.start < end }.map { it.id }.distinct()
            val text = ids.joinToString("、") { display(it, names) }
            return when (ids.size) {
                0 -> "发言人待确认"
                1 -> text
                else -> "本段含多种声音 · $text"
            }
        }
    }
}
