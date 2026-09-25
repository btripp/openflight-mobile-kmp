// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import android.speech.tts.Voice as PlatformVoice

/**
 * [SpeechEngine] over [TextToSpeech] (plan F4): a call-out is spoken with
 * [AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] (the same usage turn-by-turn navigation
 * uses) and transient "may duck" audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), so it ducks
 * music instead of stopping it, and the focus is released as soon as the utterance finishes or
 * errors.
 *
 * [TextToSpeech] itself is created lazily, on the first [speak], not in the constructor: building
 * it eagerly (e.g. from a Koin `single`) would start the platform TTS service before the user has
 * ever enabled call-outs.
 *
 * @param context any context; only its application context is kept (same pattern as
 *   `core:ble`'s `BleShotTransport(context, ...)` factory).
 */
class AndroidSpeechEngine(
    context: Context,
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val mutableIsAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = mutableIsAvailable.asStateFlow()

    private val mutableVoices = MutableStateFlow<List<Voice>>(emptyList())
    override val voices: Flow<List<Voice>> = mutableVoices.asStateFlow()

    private var textToSpeech: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var initialization: CompletableDeferred<TextToSpeech?>? = null

    /** Builds [TextToSpeech] on first use and waits for `onInit`; every later call reuses it. */
    private suspend fun engine(): TextToSpeech? = textToSpeech ?: initialization?.await() ?: initializeEngine()

    /** Starts (and records) a new [TextToSpeech] initialization; only [engine] calls this. */
    private suspend fun initializeEngine(): TextToSpeech? {
        val deferred = CompletableDeferred<TextToSpeech?>()
        initialization = deferred
        lateinit var tts: TextToSpeech
        tts =
            TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts.setAudioAttributes(audioAttributes)
                    textToSpeech = tts
                    mutableIsAvailable.value = true
                    mutableVoices.value = tts.voices.orEmpty().map(PlatformVoice::toVoice)
                    deferred.complete(tts)
                } else {
                    mutableIsAvailable.value = false
                    deferred.complete(null)
                }
            }
        return deferred.await()
    }

    override suspend fun speak(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ) {
        val tts = engine() ?: return
        voiceId
            ?.let { id -> tts.voices?.firstOrNull { it.name == id } }
            ?.let(tts::setVoice)
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        tts.setOnUtteranceProgressListener(FocusReleasingListener())
        requestAudioFocus()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString())
    }

    override fun stop() {
        textToSpeech?.stop()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
    }

    /** Releases the transient audio focus as soon as the utterance stops, however it stopped. */
    private inner class FocusReleasingListener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = abandonAudioFocus()

        @Deprecated("Overrides UtteranceProgressListener.onError(String), kept only because it is still abstract.")
        @Suppress("DEPRECATION")
        override fun onError(utteranceId: String?) = abandonAudioFocus()

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) = abandonAudioFocus()
    }
}

private val PREMIUM_QUALITY_THRESHOLD = PlatformVoice.QUALITY_VERY_HIGH
private val ENHANCED_QUALITY_THRESHOLD = PlatformVoice.QUALITY_HIGH

private fun PlatformVoice.toVoice(): Voice =
    Voice(
        id = name,
        displayName = name,
        locale = locale.toLanguageTag(),
        quality =
            when {
                quality >= PREMIUM_QUALITY_THRESHOLD -> VoiceQuality.PREMIUM
                quality >= ENHANCED_QUALITY_THRESHOLD -> VoiceQuality.ENHANCED
                else -> VoiceQuality.DEFAULT
            },
    )
