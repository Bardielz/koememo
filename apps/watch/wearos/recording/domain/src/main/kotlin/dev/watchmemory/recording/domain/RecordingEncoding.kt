package dev.watchmemory.recording.domain

enum class RecordingEncoding(val extension: String, val rates: List<Int>) {
    AAC( "aac", listOf(32000, 48000)),
    OPUS( "ogg", listOf(24000, 32000));
    val defaultBitrate get() = if (this == AAC) 48000 else 24000
    companion object {
        fun effective(enabled: Boolean, selected: String?) =
            if (enabled) entries.firstOrNull { it.name == selected } ?: AAC else AAC
        fun bitrate(enabled: Boolean, encoding: RecordingEncoding, selected: Int) =
            if (!enabled) 48000 else if (selected in encoding.rates) selected else encoding.defaultBitrate
    }
}
