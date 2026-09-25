// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How good a [Voice] sounds, mapped from each platform's own quality scale (plan F4). */
enum class VoiceQuality {
    DEFAULT,
    ENHANCED,
    PREMIUM,
}

/**
 * A synthesizer voice the platform offers, ported from Android's `android.speech.tts.Voice` and
 * iOS's `AVSpeechSynthesisVoice`.
 *
 * @property id the identifier [SpeechEngine.speak] takes as `voiceId` (Android's `Voice.getName()`,
 *   iOS's `AVSpeechSynthesisVoice.identifier`).
 * @property displayName a human-readable name for a voice picker.
 * @property locale a BCP 47 language tag (e.g. `"en-US"`).
 */
data class Voice(
    val id: String,
    val displayName: String,
    val locale: String,
    val quality: VoiceQuality,
)

/**
 * Text-to-speech, behind a platform-neutral interface (plan F4, §1 "Speech"): the Android actual
 * wraps `android.speech.tts.TextToSpeech`, the iOS actual wraps `AVSpeechSynthesizer`. Neither
 * platform type appears in this API (plan invariant: no Android types in a `core:*` public API).
 *
 * Both actuals initialize lazily, on the first [speak] call, so building the engine (e.g. eagerly,
 * from a Koin `single`) never touches the platform TTS subsystem until a call-out is actually
 * spoken.
 */
interface SpeechEngine {
    /**
     * The platform's installed voices. Empty until the engine finishes its lazy initialization (or
     * if initialization fails), then holds every voice the platform reports.
     */
    val voices: Flow<List<Voice>>

    /** Whether the engine finished initializing and is ready to [speak]. Starts `false`. */
    val isAvailable: StateFlow<Boolean>

    /**
     * Speaks [text]. A `null` or unrecognized [voiceId] falls back to the platform default voice.
     * [rate] and [pitch] are relative to the platform default (`1f` = unchanged); each actual maps
     * them onto its own scale.
     *
     * Suspends until the engine has initialized and the utterance has been *handed to* the
     * platform (not until speech finishes), so a caller can immediately [stop] a stale utterance
     * without awaiting the whole thing. A failed initialization returns without speaking.
     */
    suspend fun speak(
        text: String,
        voiceId: String? = null,
        rate: Float = 1f,
        pitch: Float = 1f,
    )

    /** Stops the current utterance, if any. Safe to call when nothing is speaking. */
    fun stop()
}
