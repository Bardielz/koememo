package dev.watchmemory.recording.infrastructure.android

import android.content.Context
import android.content.Intent
import dev.watchmemory.recording.application.*

class IntentRecordingControls(private val context: Context) : RecordingControls {
    override fun send(command: RecordingCommand) {
        val intent = Intent(context, dev.watchmemory.RecordingService::class.java).setAction(command.name)
        if (command == RecordingCommand.START) context.startForegroundService(intent) else context.startService(intent)
    }
}
