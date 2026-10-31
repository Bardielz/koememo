package dev.watchmemory.recording.application

import org.junit.Assert.*
import org.junit.Test

class RecordingDiagnosticsTest {
    private class Clock : DiagnosticClock {
        var wall = 0L; var cpu = 0L; var reads = 0
        override fun wallNanos(): Long { reads++; return wall }
        override fun threadCpuNanos(): Long { reads++; return cpu }
    }
    private class Trace : DiagnosticTrace {
        var depth = 0; var calls = 0
        override fun begin(name: String) { depth++; calls++ }
        override fun end() { depth-- }
    }
    @Test fun disabledDoesNotReadClockOrTraceAndReturnsValue() {
        val clock = Clock(); val trace = Trace(); val stats = RecordingDiagnostics(false, clock, trace)
        assertEquals(42, stats.measure(RecordingStage.CAPTURE_READ) { 42 })
        assertEquals(0, clock.reads); assertEquals(0, trace.calls)
        assertTrue(stats.snapshot().isEmpty())
    }
    @Test fun recordsWaitSeparatelyFromCpuAndPreservesExceptions() {
        val clock = Clock(); val trace = Trace(); val stats = RecordingDiagnostics(true, clock, trace)
        stats.measure(RecordingStage.CAPTURE_READ) { clock.wall += 1000; clock.cpu += 10 }
        val failure = IllegalStateException("read failed")
        val caught = assertThrows(IllegalStateException::class.java) {
            stats.measure(RecordingStage.CAPTURE_READ) { clock.wall += 2000; clock.cpu += 20; throw failure }
        }
        assertSame(failure, caught); assertEquals(0, trace.depth)
        val values = stats.snapshot()
        assertEquals(2L, values["capture_read_calls"])
        assertEquals(3000L, values["capture_read_wall_ns"])
        assertEquals(30L, values["capture_read_cpu_ns"])
        assertEquals(2000L, values["capture_read_max_wall_ns"])
        assertEquals(values, stats.snapshot()) // Reports are cumulative, not destructive.
    }
    @Test fun writerDecoratorPreservesPayloadSyncAndClose() {
        val stats = RecordingDiagnostics(true, Clock(), Trace())
        val events = mutableListOf<String>()
        val bytes = byteArrayOf(1, 2, 3)
        val format = EncodedAudioFormat(16000, 1, null)
        val delegate = object : RecordingWriter {
            override fun configure(format: EncodedAudioFormat) { events += "configure" }
            override fun write(frame: ByteArray, ptsUs: Long, length: Int) {
                assertSame(bytes, frame); assertEquals(123L, ptsUs); assertEquals(2, length)
                events += "write"
            }
            override fun sync() { events += "sync" }
            override fun close() { events += "close" }
        }
        DiagnosticRecordingWriter(delegate, stats).use { it.configure(format); it.write(bytes, 123, 2); it.sync() }
        assertEquals(listOf("configure", "write", "sync", "close"), events)
        assertEquals(1L, stats.snapshot()["close_calls"])
    }
}
