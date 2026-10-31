package dev.watchmemory.recording.infrastructure.android

import android.os.Debug
import android.os.SystemClock
import android.os.Trace
import dev.watchmemory.recording.application.*

object AndroidDiagnosticClock : DiagnosticClock {
    override fun wallNanos() = SystemClock.elapsedRealtimeNanos()
    override fun threadCpuNanos() = Debug.threadCpuTimeNanos()
}
object AndroidDiagnosticTrace : DiagnosticTrace {
    override fun begin(name: String) = Trace.beginSection(name)
    override fun end() = Trace.endSection()
}
