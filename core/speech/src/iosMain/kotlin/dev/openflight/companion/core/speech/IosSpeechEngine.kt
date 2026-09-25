// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQuality
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * [SpeechEngine] over `AVSpeechSynthesizer` (plan F4, amendment A16): the audio session uses
 * category `.playback` with `.duckOthers` and `.interruptSpokenAudioAndMixWithOthers`, so a
 * call-out plays even with the silent switch on — the same behaviour a turn-by-turn navigation or
 * coaching app uses — and ducks any other audio instead of stopping it. The session is
 * deactivated with `.notifyOthersOnDeactivation` right after each utterance finishes or is
 * cancelled, so whatever it ducked resumes at full volume.
 *
 * Activating/deactivating the session is best-effort: a failure (read from the `error:` out
 * param) is dropped rather than surfaced, since it just means the utterance plays (or stops
 * ducking) under whatever session config was already active — there's nothing more useful to do
 * with it here.
 *
 * `AVSpeechSynthesizer` needs no async setup, so [isAvailable] is `true` from construction; the
 * [voices] catalog is read once, from `AVSpeechSynthesisVoice.speechVoices()`.
 */
class IosSpeechEngine(
    private val synthesizer: AVSpeechSynthesizer = AVSpeechSynthesizer(),
) : SpeechEngine {
    private val mutableIsAvailable = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = mutableIsAvailable.asStateFlow()

    private val mutableVoices = MutableStateFlow<List<Voice>>(emptyList())
    override val voices: Flow<List<Voice>> = mutableVoices.asStateFlow()

    // AVSpeechSynthesizer holds its delegate weakly; this property keeps it alive.
    private val delegate =
        object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
            @ObjCSignatureOverride
            override fun speechSynthesizer(
                synthesizer: AVSpeechSynthesizer,
                didFinishSpeechUtterance: AVSpeechUtterance,
            ) = deactivateSession()

            @ObjCSignatureOverride
            override fun speechSynthesizer(
                synthesizer: AVSpeechSynthesizer,
                didCancelSpeechUtterance: AVSpeechUtterance,
            ) = deactivateSession()
        }

    init {
        synthesizer.delegate = delegate
        @Suppress("UNCHECKED_CAST")
        val voices = AVSpeechSynthesisVoice.speechVoices() as List<AVSpeechSynthesisVoice>
        mutableVoices.value = voices.map { it.toVoice() }
    }

    override suspend fun speak(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ) {
        activateSession()
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = voiceId?.let { id -> AVSpeechSynthesisVoice.voiceWithIdentifier(id) }
        utterance.rate =
            (AVSpeechUtteranceDefaultSpeechRate * rate)
                .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        utterance.pitchMultiplier = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        deactivateSession()
    }

    /**
     * `error:`-out-param calls stay as ordinary Kotlin/Native cinterop calls (no automatic
     * `throws` conversion here), so a failure is read from [errorPtr] rather than caught; both
     * failures are ignored (best-effort, see the class doc) since there is nothing more useful to
     * do with them here.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun activateSession() =
        memScoped {
            val session = AVAudioSession.sharedInstance()
            val errorPtr = alloc<ObjCObjectVar<NSError?>>().ptr
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                withOptions =
                    AVAudioSessionCategoryOptionDuckOthers or
                        AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers,
                error = errorPtr,
            )
            session.setActive(true, error = errorPtr)
            Unit
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun deactivateSession() =
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>().ptr
            AVAudioSession.sharedInstance().setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = errorPtr,
            )
            Unit
        }

    private companion object {
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}

private fun AVSpeechSynthesisVoice.toVoice(): Voice =
    Voice(
        id = identifier,
        displayName = name,
        locale = language,
        quality = quality.toVoiceQuality(),
    )

private fun AVSpeechSynthesisVoiceQuality.toVoiceQuality(): VoiceQuality =
    when (this) {
        AVSpeechSynthesisVoiceQualityPremium -> VoiceQuality.PREMIUM
        AVSpeechSynthesisVoiceQualityEnhanced -> VoiceQuality.ENHANCED
        else -> VoiceQuality.DEFAULT
    }
