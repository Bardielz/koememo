package dev.koememo.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeakerSessionTest {
    @Test fun localIdsCanSwapWithoutChangingSessionIdentity() {
        val profiles = SpeakerProfiles(mutableListOf())
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(mapOf(0 to 1, 1 to 2), profiles.assign(mapOf(0 to a, 1 to b)))
        assertEquals(mapOf(0 to 2, 1 to 1), profiles.assign(mapOf(0 to b, 1 to a)))
        val close = profiles.assign(mapOf(0 to a, 1 to a))
        assertNotEquals(close[0], close[1])
        assertFalse(profiles.assign(mapOf(7 to floatArrayOf(0f, 0f))).containsKey(7))
    }
    @Test fun overlappingSpeechIsExcludedFromEnrollmentAndShownInTranscript() {
        val turns = listOf(SpeakerTurn(0, 3000, 1), SpeakerTurn(500, 1800, 2))
        assertEquals(1800 to 3000, longestSolo(turns, 1))
        assertNull(longestSolo(turns, 2))
        val names = JSONObject().put("1", "Alex")
        assertEquals("Alex", SpeakerStore.label(turns, 0, 500, names))
        assertEquals("本段含多种声音 · Alex、发言人 2", SpeakerStore.label(turns, 500, 900, names))
        assertEquals("发言人待确认", SpeakerStore.label(turns, 3000, 4000, names))
    }
}
