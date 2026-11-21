package dev.watchmemory.recording.application

import org.junit.Assert.assertEquals
import org.junit.Test

class WearStartCandidateTest {
    @Test fun onlyConfirmedOffToOnTransitionsTrigger() {
        val detector = WearStartCandidate()
        assertEquals(listOf(false, false, false, true, false, false, false, false),
            listOf(1f, 1f, 0f, 1f, 1f, 0f, 2f, 1f).map(detector::observe))
    }
}
