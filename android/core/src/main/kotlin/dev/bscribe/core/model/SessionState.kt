package dev.bscribe.core.model

/**
 * Lifecycle of a note-taking session.
 *
 * RECORDING → PAUSED → RECORDING … → STOPPED → TRANSCRIBING → TRANSCRIBED → REVIEWED
 *
 * A session interrupted by a crash is moved to STOPPED at next app start,
 * after its audio has been repaired ([dev.bscribe.core.audio.WavRepair]).
 */
enum class SessionState {
    RECORDING,
    PAUSED,
    STOPPED,
    TRANSCRIBING,
    TRANSCRIBED,
    REVIEWED;

    val isActive: Boolean get() = this == RECORDING || this == PAUSED
}
