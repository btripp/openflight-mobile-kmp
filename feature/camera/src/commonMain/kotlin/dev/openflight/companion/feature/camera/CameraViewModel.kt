// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The camera screen's state holder (plan R6b): toggles ball detection and the live stream, and
 * exposes the live MJPEG feed as JPEG [frames] that each platform decodes. Wi-Fi only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(
    private val piSession: PiSessionRepository,
) : ViewModel() {
    private val streamError = MutableStateFlow<String?>(null)

    /** Bumped by [CameraEvent.RetryStream] so the stream reopens even though nothing else changed. */
    private val streamAttempt = MutableStateFlow(0)

    val uiState: StateFlow<CameraUiState> =
        combine(piSession.linkState, piSession.cameraStatus, streamError) { link, camera, error ->
            buildState(PiFeatureAvailability.of(link), camera, error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = buildState(PiFeatureAvailability.of(piSession.linkState.value), CameraStatus(), null),
        )

    /**
     * The live feed's JPEG frames, one [ByteArray] per frame, while the phase is
     * [CameraPhase.STREAMING]. Hot and shared: every collector sees the same HTTP stream, which
     * opens with the first collector and closes shortly after the last one leaves, or when the
     * stream stops. A failure moves the state to [CameraPhase.STREAM_ERROR].
     */
    val frames: Flow<ByteArray> =
        combine(uiState.map { it.phase == CameraPhase.STREAMING }, streamAttempt, ::Pair)
            .distinctUntilChanged()
            .flatMapLatest { (streaming, _) ->
                if (!streaming) {
                    emptyFlow()
                } else {
                    piSession.cameraFrames().catch { failure ->
                        streamError.value = failure.message ?: STREAM_FAILED
                    }
                }
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS))

    private val cameraEffects = Channel<CameraEffect>(Channel.BUFFERED)
    val effects: Flow<CameraEffect> = cameraEffects.receiveAsFlow()

    init {
        // Like the web UI: a new stream start clears the previous stream error.
        viewModelScope.launch {
            var wasStreaming = false
            piSession.cameraStatus.collect { camera ->
                if (camera.streaming && !wasStreaming) streamError.value = null
                wasStreaming = camera.streaming
            }
        }
    }

    fun onEvent(event: CameraEvent) {
        when (event) {
            CameraEvent.ToggleCamera -> {
                send { piSession.toggleCamera() }
            }

            CameraEvent.ToggleStream -> {
                send { piSession.toggleCameraStream() }
            }

            CameraEvent.RetryStream -> {
                streamError.value = null
                streamAttempt.value++
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Every command failure becomes a message.
    private fun send(command: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                cameraEffects.send(CameraEffect.Message(failure.message ?: COMMAND_FAILED))
            }
        }
    }

    companion object {
        const val COMMAND_FAILED = "The Pi didn't accept that."
        const val STREAM_FAILED = "Could not load camera stream"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val PERCENT = 100

        internal fun buildState(
            availability: PiFeatureAvailability,
            camera: CameraStatus,
            streamError: String?,
        ): CameraUiState {
            val phase = phaseOf(availability, camera, streamError)
            val percent = (camera.ballConfidence * PERCENT).roundToInt()
            val toggleCamera = toggleCameraAvailability(availability, camera)
            return CameraUiState(
                phase = phase,
                availability = availability,
                cameraAvailable = camera.available,
                enabled = camera.enabled,
                streaming = camera.streaming,
                ballDetected = camera.ballDetected,
                ballConfidencePercent = percent,
                statusText = statusText(camera, percent),
                cameraError = camera.error,
                streamError = streamError.takeIf { phase == CameraPhase.STREAM_ERROR },
                toggleCamera = toggleCamera,
                toggleStream =
                    if (toggleCamera.isAvailable && !camera.enabled) {
                        PiFeatureAvailability.Unavailable(CameraUiState.CAMERA_DISABLED)
                    } else {
                        toggleCamera
                    },
            )
        }

        /** `CameraFeed.tsx`'s branches, in order. */
        private fun phaseOf(
            availability: PiFeatureAvailability,
            camera: CameraStatus,
            streamError: String?,
        ): CameraPhase =
            when {
                !availability.isAvailable -> CameraPhase.OFFLINE
                !camera.available -> CameraPhase.UNAVAILABLE
                !camera.enabled -> CameraPhase.DISABLED
                !camera.streaming -> CameraPhase.PAUSED
                streamError != null -> CameraPhase.STREAM_ERROR
                else -> CameraPhase.STREAMING
            }

        private fun toggleCameraAvailability(
            availability: PiFeatureAvailability,
            camera: CameraStatus,
        ): PiFeatureAvailability =
            when {
                !availability.isAvailable -> availability
                !camera.available -> PiFeatureAvailability.Unavailable(CameraUiState.CAMERA_NOT_AVAILABLE)
                else -> PiFeatureAvailability.Available
            }

        /** `BallDetectionIndicator.tsx`'s `getStatusText`. */
        private fun statusText(
            camera: CameraStatus,
            percent: Int,
        ): String =
            when {
                !camera.enabled -> "Camera Off"
                camera.ballDetected -> "Ball $percent%"
                else -> "No Ball"
            }
    }
}
