package dev.koememo.phone

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import android.util.AtomicFile
import dev.koememo.sync.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class TranscriptReadingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    @Test fun railNavigatesAndFiltersSpeakerParagraphs() {
        val audio = File(ctx.filesDir, "audio").apply { mkdirs() }
        val dir = Files.createTempDirectory(audio.toPath(), "rail").toFile()
        val file = File(dir, "fixture.aac").apply { writeText(dir.name) }
        val clip = AudioSession.load(dir).clips.single()
        val choice = AsrChoice.legacy
        val windows = JSONArray()
        val turns = ArrayList<SpeakerTurn>()
        repeat(40) { i ->
            val row = JSONObject().put("start_ms", i * 15000).put("text", "段落 $i 原音")
            // Older checkpoints may only record the start of a 15-second window.
            if (i > 0) row.put("end_ms", (i + 1) * 15000)
            windows.put(row)
            turns.add(SpeakerTurn(i * 15000, (i + 1) * 15000, 1 + i % 2))
        }
        val checkpoint = File(ctx.filesDir, "transcripts/${choice.key}-15s-v1-${clip.hash}.json")
        writeJson(AtomicFile(checkpoint), JSONObject().put("windows", windows))
        val store = SpeakerStore(ctx, dir)
        store.save(store.analysis(), clip, turns, SpeakerProfiles(mutableListOf()))
        store.rename(1,"甲"); store.rename(2,"乙")
        val settings = ctx.getSharedPreferences("asr-model", 0)
        val previous = settings.getString("selected", null)
        settings.edit().putString("selected", choice.key).commit()
        val reading = ctx.getSharedPreferences("transcript-reading", 0)
        val previousSide = reading.getBoolean("rail_right", false)
        reading.edit().putBoolean("rail_right", false).commit()
        val activity = inst.startActivitySync(Intent(ctx, TranscriptionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("path", dir.path))
        try {
            val root = activity.window.decorView
            lateinit var list: ListView
            for (i in 0 until 100) {
                var ready = false
                inst.runOnMainSync { list = views(root).filterIsInstance<ListView>().first(); ready = list.count == 40 }
                if (ready) break
                SystemClock.sleep(50)
            }
            inst.runOnMainSync {
                assertEquals(40, list.count)
                val rail = views(root).filterIsInstance<ParagraphRail>().single()
                assertEquals(View.VISIBLE, rail.visibility)
                assertTrue(rail.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 20f)
                }))
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals(19, list.firstVisiblePosition)
                assertTrue((list.getChildAt(0) as TextView).text.startsWith("乙"))
                val toolbar = views(root).filterIsInstance<com.google.android.material.appbar.MaterialToolbar>().first()
                toolbar.showOverflowMenu()
            }
            clickAccessibleText("段落导航位置")
            clickAccessibleText("右侧")
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val rail = views(root).filterIsInstance<ParagraphRail>().single()
                assertTrue(rail.left >= list.right)
                assertEquals(19, list.firstVisiblePosition)
                assertTrue(reading.getBoolean("rail_right", false))
                views(root).filterIsInstance<EditText>().first().setText("段落 19 ")
                assertEquals(1, list.count)
                assertEquals(View.GONE, views(root).filterIsInstance<ParagraphRail>().single().visibility)
            }
        } finally {
            inst.runOnMainSync { activity.finish() }
            settings.edit().putString("selected", previous).commit()
            reading.edit().putBoolean("rail_right", previousSide).commit()
            checkpoint.delete(); file.delete(); dir.delete()
        }
    }

    private fun clickAccessibleText(text: String) {
        repeat(75) {
            var node = inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(60)
        }
        fail("找不到 $text")
    }

    @Test fun importedEncodersRankRelatedTextOnAndroid() {
        val encoders = listOf(TranscriptModel.EMBED1, TranscriptModel.EMBED2)
        org.junit.Assume.assumeTrue(encoders.all { it.file(ctx).isFile })
        for (encoder in encoders) TranscriptWorker(ctx, encoder).use { worker ->
            fun vector(text: String) = embeddingVector(worker.run(text))
            val q = vector("task: search result | query: 酒店怎么连接 Wi-Fi")
            fun score(document: String): Double {
                val v = vector("title: none | text: $document")
                return q.indices.sumOf { q[it].toDouble() * v[it] }
            }
            assertTrue(score("酒店的无线网络可以调试手表") > score("今天晚餐是番茄炒蛋"))
        }
    }

    @Test fun indexSeparatesModelsAndRollsBackFailedRefresh() {
        val scope = java.util.UUID.randomUUID().toString()
        val clip = SessionClip(File("source.ogg"), "sourcehash", null, null, false)
        val line = TranscriptLine(clip, 0, 15000, "原话", null, listOf(1), "甲", null, null, "")
        val unit = ByteBuffer.allocate(3072).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).array()
        TranscriptIndex(ctx).use { store ->
            val db = store.writableDatabase
            fun hashes() = db.rawQuery("SELECT text_sha FROM document WHERE session=?", arrayOf(scope)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            try {
                for (model in listOf(TranscriptModel.EMBED1, TranscriptModel.EMBED2)) {
                    store.build(scope, "asr", model, listOf(line), { unit }, {})
                    store.build(scope, "asr", model, listOf(line), { error("重复计算") }, {})
                }
                assertEquals(listOf(stableDigest("原话"),stableDigest("原话")), hashes())
                val edited = line.copy(edit="修改")
                try {
                    store.build(scope, "asr", TranscriptModel.EMBED2, listOf(edited, line.copy(start=15000)),
                        { if (it.endsWith("修改")) unit else error("中断") }, {})
                    fail("应该回滚")
                } catch (_: IllegalStateException) {}
                assertTrue(hashes().all { it == stableDigest("原话") })
                store.build(scope, "asr", TranscriptModel.EMBED2, listOf(edited), { unit }, {})
                assertEquals(setOf(stableDigest("原话"),stableDigest("修改")), hashes().toSet())
            } finally { db.delete("document","session=?",arrayOf(scope)) }
        }
    }
}
