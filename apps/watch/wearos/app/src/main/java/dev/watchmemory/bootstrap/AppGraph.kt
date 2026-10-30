package dev.watchmemory.bootstrap

import android.content.Context
import android.media.AudioManager
import dev.watchmemory.recording.application.*
import dev.watchmemory.recording.infrastructure.settings.SharedPreferencesSettings
import dev.watchmemory.recording.infrastructure.storage.FileRecordingRepository
import java.util.UUID
import dev.watchmemory.recording.infrastructure.storage.RecordingWriterFactory
import dev.watchmemory.recording.infrastructure.android.IntentRecordingControls
import dev.watchmemory.recording.infrastructure.audio.AndroidRecordingPlayback

/** Explicit composition root; Android dependencies never enter domain/application modules. */
class AppGraph private constructor(context: Context) {
    val runtime = RecordingRuntime()
    val state: RecordingReadModel get() = runtime
    val repository = FileRecordingRepository(context)
    val writerFactory = RecordingWriterFactory(repository)
    val controls: RecordingControls = IntentRecordingControls(context)
    val playback: RecordingPlayback = AndroidRecordingPlayback()
    val sessions = RecordingSessions(repository) { "run-" + UUID.randomUUID().toString().take(8) }
    val settings = RecordingSettings(SharedPreferencesSettings(context), state,
        context.getSystemService(AudioManager::class.java).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
