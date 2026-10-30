package dev.watchmemory.recording.application

enum class RecordingCommand { START, PAUSE, FINISH, MARK }
fun interface RecordingControls { fun send(command: RecordingCommand) }
interface RecordingPlayback {
    val isPlaying: Boolean
    fun play(path: String, completed: (String?) -> Unit)
    fun stop()
}
