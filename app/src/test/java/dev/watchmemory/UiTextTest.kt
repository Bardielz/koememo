package dev.watchmemory

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
