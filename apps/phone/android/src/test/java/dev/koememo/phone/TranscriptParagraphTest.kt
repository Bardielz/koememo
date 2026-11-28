package dev.koememo.phone

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TranscriptParagraphTest {
    private val clip = SessionClip(File("x.aac"), "hash", null, null, false)
    private fun line(t: Int, vararg ids: Int, text: String = "短句。") = TranscriptLine(clip, t, t + 15000, text,
        null, ids.toList(), ids.joinToString(), null, null, "")
    @Test fun paragraphsKeepSourceWindowsAndSplitTurnsGapsAndAmbiguity() {
        val rows = listOf(line(0, 1), line(15000, 1), line(30000, 2), line(48000, 2),
            line(63000, 1, 2), line(78000, 1, 2), line(93000, 1))
        val result = transcriptParagraphs(rows)
        assertEquals(listOf(2, 1, 1, 1, 1, 1), result.map { it.size })
        assertEquals(rows, result.flatten())
        assertEquals(" ", paragraphSeparator("hello.", "Tomorrow"))
        assertEquals("", paragraphSeparator("你好。", "明天"))
    }
    @Test fun longMonologueBreaksAfterSentenceAndRetainsEdit() {
        val rows = listOf(line(0, 1).copy(edit = "文".repeat(120) + "。"), line(15000, 1))
        assertEquals(2, transcriptParagraphs(rows).size)
        assertTrue(meetingPrompt("notes", rows).contains(rows.first().edit!!))
    }
    @Test fun notesReferenceOnlySuppliedTimes() {
        val rows = listOf(line(0, 1), line(15000, 2))
        assertEquals(15000, meetingNotes("NOTE [0:00:15] (ACTION) 交报告\nNEXT", rows).single().start)
        assertTrue(meetingNotes("NEXT", rows).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { meetingNotes("NOTE [0:00:08] (ACTION) 交报告\nNEXT",rows) }
        assertThrows(IllegalArgumentException::class.java) { meetingNotes("NOTE [0:00:15] (ACTION) 交报告", rows) }
    }
}
