// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The camera screen's state holder (plan R8c), over the backend's camera API: it **polls**
 * `GET /api/camera/preview.jpg` while [frames] is collected (the screen is visible and the link
 * is up), shows the capture settings, and prepares shot replays for the platform's native video
 * player. Wi-Fi only.
 *
 * @param previewIntervalMillis the pause between stills while live (≈3 fps).
 * @param retryIntervalMillis the pause after a refusal or failure, so an idle camera isn't hammered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(
    private val piSession: PiSessionRepository,
    private val previewIntervalMillis: Long = PREVIEW_INTERVAL_MILLIS,
    private val retryIntervalMillis: Long = RETRY_INTERVAL_MILLIS,
) : ViewModel() {
    /** The last poll's outcome; `null` before the first answer of this visit. */
    private val preview = MutableStateFlow<PreviewOutcome?>(null)
    private val replay = MutableStateFlow<CameraReplayState>(CameraReplayState.Idle)

    private val replays = piSession.sessionShots.map(::replayRows).distinctUntilChanged()

    val uiState: StateFlow<CameraUiState> =
        combine(piSession.linkState, piSession.cameraCaptureSettings, preview, replays, replay) {
            link,
            settings,
            outcome,
            rows,
            replayState,
            ->
            buildState(PiFeatureAvailability.of(link), settings, outcome, rows, replayState)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue =
                buildState(
                    PiFeatureAvailability.of(piSession.linkState.value),
                    null,
                    null,
                    emptyList(),
                    CameraReplayState.Idle,
                ),
        )

    /**
     * Preview stills (JPEG bytes), polled only while this flow is collected **and** the link is
     * connected: collect it only while the screen is visible. Shared, so every collector sees the
     * same poll; polling stops as soon as the last collector leaves.
     */
    val frames: Flow<ByteArray> =
        piSession.linkState
            .map { it == PiLinkState.Connected }
            .distinctUntilChanged()
            .flatMapLatest { connected -> if (connected) poll() else emptyFlow() }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed())

    private val cameraEffects = Channel<CameraEffect>(Channel.BUFFERED)
    val effects: Flow<CameraEffect> = cameraEffects.receiveAsFlow()

    init {
        viewModelScope.launch {
            piSession.notices.collect { notice ->
                if (notice is PiNotice.CameraSettingsFailed) cameraEffects.send(CameraEffect.Message(notice.message))
            }
        }
    }

    fun onEvent(event: CameraEvent) {
        when (event) {
            CameraEvent.RefreshSettings -> refreshSettings()
            is CameraEvent.PlayReplay -> playReplay(event.replayId)
            CameraEvent.DismissReplay -> replay.value = CameraReplayState.Idle
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failed poll is shown and retried.
    private fun poll(): Flow<ByteArray> =
        flow {
            preview.value = null
            while (true) {
                val outcome =
                    try {
                        PreviewOutcome.Answer(piSession.cameraPreview())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        PreviewOutcome.Failed(failure.message ?: PREVIEW_FAILED)
                    }
                preview.value = outcome
                val frame = (outcome as? PreviewOutcome.Answer)?.preview as? CameraPreview.Frame
                if (frame != null) emit(frame.jpeg)
                delay(if (frame != null) previewIntervalMillis else retryIntervalMillis)
            }
        }

    @Suppress("TooGenericExceptionCaught") // Every failure becomes the replay's error.
    private fun playReplay(replayId: String) {
        if (replay.value is CameraReplayState.Preparing) return
        val mirror =
            uiState.value.replays
                .firstOrNull { it.replayId == replayId }
                ?.mirrorHorizontal ?: false
        replay.value = CameraReplayState.Preparing(replayId)
        viewModelScope.launch {
            replay.value =
                try {
                    CameraReplayState.Ready(replayId, piSession.prepareReplay(replayId), mirror)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    CameraReplayState.Failed(replayId, failure.message ?: REPLAY_FAILED)
                }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Every command failure becomes a message.
    private fun refreshSettings() {
        viewModelScope.launch {
            try {
                piSession.refreshCameraCaptureSettings()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                cameraEffects.send(CameraEffect.Message(failure.message ?: COMMAND_FAILED))
            }
        }
    }

    /** One poll's result: the Pi's answer, or a failure to reach it. */
    internal sealed interface PreviewOutcome {
        data class Answer(
            val preview: CameraPreview,
        ) : PreviewOutcome

        data class Failed(
            val message: String,
        ) : PreviewOutcome
    }

    companion object {
        const val COMMAND_FAILED = "The Pi didn't accept that."
        const val PREVIEW_FAILED = "Couldn't load the camera preview."
        const val REPLAY_FAILED = "Couldn't prepare the replay."

        /** ≈3 fps while live (plan R8c: 2–4 fps while visible). */
        const val PREVIEW_INTERVAL_MILLIS = 333L
        const val RETRY_INTERVAL_MILLIS = 2_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        internal fun buildState(
            availability: PiFeatureAvailability,
            settings: CameraCaptureSettings?,
            outcome: PreviewOutcome?,
            replays: List<ReplayRow>,
            replay: CameraReplayState,
        ): CameraUiState {
            val phase = phaseOf(availability, settings, outcome)
            return CameraUiState(
                phase = phase,
                availability = availability,
                settings = settings,
                previewError = (outcome as? PreviewOutcome.Failed)?.message ?: unavailableText(outcome),
                replays = replays,
                replay = replay,
            )
        }

        private fun phaseOf(
            availability: PiFeatureAvailability,
            settings: CameraCaptureSettings?,
            outcome: PreviewOutcome?,
        ): CameraPhase {
            val answer = (outcome as? PreviewOutcome.Answer)?.preview
            return when {
                !availability.isAvailable -> CameraPhase.OFFLINE

                outcome is PreviewOutcome.Failed -> CameraPhase.ERROR

                answer is CameraPreview.Frame -> CameraPhase.LIVE

                answer == CameraPreview.CaptureNotEnabled -> CameraPhase.NOT_ENABLED

                answer == CameraPreview.CameraNotRunning -> CameraPhase.NOT_RUNNING

                answer is CameraPreview.Unavailable -> CameraPhase.ERROR

                // No still yet: the settings already tell a Pi without capture apart.
                settings?.available == false -> CameraPhase.NOT_ENABLED

                else -> CameraPhase.LOADING
            }
        }

        private fun unavailableText(outcome: PreviewOutcome?): String? =
            ((outcome as? PreviewOutcome.Answer)?.preview as? CameraPreview.Unavailable)?.let {
                "${it.message} (HTTP ${it.status})"
            }

        /** The session's shots that carry a capture, newest first (the session is newest first). */
        internal fun replayRows(session: List<ShotDetail>): List<ReplayRow> =
            session
                .mapNotNull { shot ->
                    shot.cameraReplay?.let { replay ->
                        ReplayRow(
                            replayId = replay.id,
                            shotNumber = shot.shotNumber,
                            timestamp = shot.timestamp,
                            // The display name ("Pitching Wedge"), never the wire value ("pw").
                            club = shot.club?.let(GolfClub::displayNameFor),
                            ballSpeedMph = shot.ballSpeedMph,
                            mirrorHorizontal = replay.displayMirrorHorizontal ?: false,
                        )
                    }
                }.take(CameraUiState.MAX_REPLAYS)
    }
}
