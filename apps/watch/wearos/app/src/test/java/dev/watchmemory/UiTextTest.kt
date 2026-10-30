package dev.watchmemory

import dev.watchmemory.recording.infrastructure.storage.*
import dev.watchmemory.recording.infrastructure.opus.*
import dev.watchmemory.recording.presentation.recording.UiText

import org.junit.Assert.*
import org.junit.Test

class UiTextTest {
    @Test fun batteryPercentIsLiteralAndNeverParsedAsFormatInstruction() {
        for (battery in listOf(0, 35, 100)) {
            val text = UiText.deviceStats(battery, 1_500_000_000)
            assertTrue(text.contains("$battery%"))
            assertTrue(text.contains("1500"))
        }
    }
}
