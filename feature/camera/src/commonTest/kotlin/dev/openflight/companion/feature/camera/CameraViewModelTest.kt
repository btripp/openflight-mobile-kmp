// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.data.PiCameraUnavailableException
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    private val piSession = FakePiSessionRepository()
    private lateinit var viewModel: CameraViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = CameraViewModel(piSession)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun thePhasesFollowTheWebUiBranches() {
        val connected = PiFeatureAvailability.Available

        assertThat(CameraViewModel.buildState(connected, CameraStatus(), null).phase).isEqualTo(CameraPhase.UNAVAILABLE)
        assertThat(CameraViewModel.buildState(connected, CameraStatus(available = true), null).phase)
            .isEqualTo(CameraPhase.DISABLED)
        assertThat(CameraViewModel.buildState(connected, CameraStatus(available = true, enabled = true), null).phase)
            .isEqualTo(CameraPhase.PAUSED)
        val streaming = CameraStatus(available = true, enabled = true, streaming = true)
        assertThat(CameraViewModel.buildState(connected, streaming, null).phase).isEqualTo(CameraPhase.STREAMING)
        assertThat(CameraViewModel.buildState(connected, streaming, "boom").phase).isEqualTo(CameraPhase.STREAM_ERROR)
    }

    @Test
    fun ballDetectionShowsStatusTextAndConfidence() =
        runTest {
            piSession.cameraStatus.value =
                CameraStatus(available = true, enabled = true, ballDetected = true, ballConfidence = 0.866)

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.ballDetected }

                assertThat(state.ballConfidencePercent).isEqualTo(87)
                assertThat(state.statusText).isEqualTo("Ball 87%")
                assertThat(state.toggleStream).isEqualTo(PiFeatureAvailability.Available)
            }
        }

    @Test
    fun togglesAreDisabledWithAReasonWhenTheyCannotRun() =
        runTest {
            piSession.linkState.value = PiLinkState.WifiOnly
            viewModel.uiState.testIgnoringRest {
                val offline = awaitUntil { it.availability.disabledReason == "Requires Wi-Fi" }
                assertThat(offline.phase).isEqualTo(CameraPhase.OFFLINE)
                assertThat(offline.toggleCamera.disabledReason).isEqualTo("Requires Wi-Fi")

                piSession.linkState.value = PiLinkState.Connected
                val noCamera = awaitUntil { it.availability.isAvailable }
                assertThat(noCamera.toggleCamera.disabledReason).isEqualTo(CameraUiState.CAMERA_NOT_AVAILABLE)

                piSession.cameraStatus.value = CameraStatus(available = true)
                val disabled = awaitUntil { it.cameraAvailable }
                assertThat(disabled.toggleCamera).isEqualTo(PiFeatureAvailability.Available)
                assertThat(disabled.toggleStream.disabledReason).isEqualTo(CameraUiState.CAMERA_DISABLED)
                assertThat(disabled.statusText).isEqualTo("Camera Off")
            }
        }

    @Test
    fun togglesSendTheirCommands() =
        runTest {
            viewModel.onEvent(CameraEvent.ToggleCamera)
            viewModel.onEvent(CameraEvent.ToggleStream)

            assertThat(piSession.commands).containsExactly("toggle_camera", "toggle_camera_stream")
        }

    @Test
    fun aFailedToggleBecomesAMessage() =
        runTest {
            piSession.linkState.value = PiLinkState.Connecting
            viewModel.effects.test {
                viewModel.onEvent(CameraEvent.ToggleCamera)

                assertThat(awaitItem()).isEqualTo(CameraEffect.Message("Not connected to the Pi's live session yet."))
            }
        }

    @Test
    fun framesFlowOnlyWhileStreaming() =
        runTest {
            piSession.frames = flowOf(byteArrayOf(1), byteArrayOf(2))

            viewModel.frames.test {
                expectNoEvents()

                piSession.cameraStatus.value = CameraStatus(available = true, enabled = true, streaming = true)

                assertThat(awaitItem().toList()).containsExactly(1.toByte())
                assertThat(awaitItem().toList()).containsExactly(2.toByte())
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(piSession.cameraFrameCollections).isEqualTo(1)
        }

    @Test
    fun aStreamFailureShowsTheErrorAndRetryReopensIt() =
        runTest {
            piSession.frames = flow { throw PiCameraUnavailableException(503, "Camera not available") }
            piSession.cameraStatus.value = CameraStatus(available = true, enabled = true, streaming = true)

            viewModel.uiState.testIgnoringRest {
                viewModel.frames.test {
                    val failed = this@testIgnoringRest.awaitUntil { it.phase == CameraPhase.STREAM_ERROR }
                    assertThat(failed.streamError).isEqualTo("Camera not available")

                    piSession.frames = flowOf(byteArrayOf(7))
                    viewModel.onEvent(CameraEvent.RetryStream)

                    assertThat(awaitItem().toList()).containsExactly(7.toByte())
                    val recovered = this@testIgnoringRest.awaitUntil { it.phase == CameraPhase.STREAMING }
                    assertThat(recovered.streamError).isNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    /** Like `test`, but tolerates the extra intermediate states `combine` may emit after the assertions. */
    private suspend fun <T> Flow<T>.testIgnoringRest(block: suspend ReceiveTurbine<T>.() -> Unit) =
        test {
            block()
            cancelAndIgnoreRemainingEvents()
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
