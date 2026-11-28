package dev.koememo.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingBrowserTest {
    @Test fun sessionDateIsTakenFromWatchAndSearchableInCommonFormats() {
        val row = sessionRow(File("20261120-005346-49f298d3"), 143)
        assertEquals("2026年11月20日 00:53", row.title)
        for (query in listOf("11/20", "11月20日", "20261120", "00:53", "49F298D3")) assertTrue(query, row.matches(query))
        assertFalse(row.matches("11-21"))
        assertEquals("old-session", sessionTitle(File("old-session")))
        assertEquals("20260230-005346-49f298d3", sessionTitle(File("20260230-005346-49f298d3")))
    }

    @Test fun legacyTimeIsLabelledAndNumericQueriesMatchTheActualSegment() {
        val file = File("run-9e8cd632-0042.aac")
        val row = segmentRow(file, 0L, "2026-11-20 10:21:07", null)
        assertEquals("片段 42", row.title)
        assertTrue(row.subtitle.startsWith("存入 "))
        assertTrue(row.matches("42"))
        assertTrue(row.matches("0042"))
        assertFalse(row.matches("7"))
        assertTrue(row.matches("AAC 9e8c"))
        assertTrue(row.matches("11/20"))
        val verified = segmentRow(file, 0L, "2026-11-20 10:21:07", "2026-11-19 08:00:00")
        assertTrue(verified.subtitle.startsWith("录于 2026-11-19"))
        assertTrue(verified.matches("11-19"))
    }
}
