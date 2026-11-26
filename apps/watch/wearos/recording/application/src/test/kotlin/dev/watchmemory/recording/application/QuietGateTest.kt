package dev.watchmemory.recording.application

import org.junit.Assert.*
import org.junit.Test

class QuietGateTest {
    private val rate = 16000
    private val quiet = ByteArray(3200)
    private fun block(amplitude: Int) = ByteArray(quiet.size) { if (it % 2 == 0) amplitude.toByte() else 0 }

    @Test fun preservesAttackAndOneSecondOfOrderedHistory() {
        val gate = QuietGate(rate)
        repeat(99) { assertSame(quiet, gate.accept(quiet, quiet.size, true)) }
        repeat(12) { val b = block(it); assertNull(gate.accept(b, b.size, true)) }
        val attack = block(0).also { for (i in 0 until 320 step 2) it[i] = 120 }
        val output = gate.accept(attack, attack.size, true)!!
        assertArrayEquals(((2..11).flatMap { block(it).asIterable() } + attack.asIterable()).toByteArray(), output)
        assertEquals(rate, gate.replaySamples)
        assertEquals(12L * 1600, gate.heldSamples)
        assertFalse(gate.paused)
    }

    @Test fun unavailableMicOpensGateAndCannotTriggerAnotherGap() {
        val gate = QuietGate(rate)
        repeat(110) { gate.accept(quiet, quiet.size, true) }
        assertTrue(gate.paused)
        assertNotNull(gate.accept(quiet, 2, false))
        repeat(110) { assertSame(quiet, gate.accept(quiet, quiet.size, false)) }
        assertFalse(gate.paused)
    }
}
