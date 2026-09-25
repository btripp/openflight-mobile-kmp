// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.speech.Voice
import kotlinx.coroutines.flow.MutableStateFlow

/** A [SpeechEngine] that records every call instead of speaking (plan F4). */
class FakeSpeechEngine(
    isAvailable: Boolean = true,
    voices: List<Voice> = emptyList(),
) : SpeechEngine {
    override val isAvailable = MutableStateFlow(isAvailable)
    override val voices = MutableStateFlow(voices)

    /** One entry per [speak] call, in order, oldest first. */
    val spoken = mutableListOf<SpokenUtterance>()

    var stopCalls = 0
        private set

    override suspend fun speak(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ) {
        spoken += SpokenUtterance(text, voiceId, rate, pitch)
    }

    override fun stop() {
        stopCalls++
    }

    data class SpokenUtterance(
        val text: String,
        val voiceId: String?,
        val rate: Float,
        val pitch: Float,
    )
}
