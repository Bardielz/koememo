package dev.watchmemory.recording.application

/** The initial event establishes a baseline; only a subsequent off -> on edge qualifies. */
class WearStartCandidate {
    private var previous: Float? = null

    fun observe(value: Float): Boolean {
        val start = previous == 0f && value == 1f
        previous = value.takeIf { it == 0f || it == 1f }
        return start
    }
}
