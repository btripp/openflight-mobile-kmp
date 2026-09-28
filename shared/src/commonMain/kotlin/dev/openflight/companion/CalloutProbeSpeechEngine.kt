// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.speech.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plan F14 (`--callout-probe`, debug launch hook only): a [SpeechEngine] that writes each call-out
 * down instead of speaking it, so an iOS UI test can read the last one off the screen (Android's
 * device tests inject `FakeSpeechEngine` through Koin instead).
 */
internal class CalloutProbeSpeechEngine : SpeechEngine {
    private val last = MutableStateFlow<String?>(null)

    /** The last call-out's text, or `null` before the first one. */
    val lastSpoken: StateFlow<String?> = last.asStateFlow()

    override val voices: StateFlow<List<Voice>> = MutableStateFlow(emptyList())
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun speak(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ) {
        last.value = text
    }

    override fun stop() = Unit
}
