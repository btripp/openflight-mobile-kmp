// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.data.WifiOnlyFeatureException
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [CameraViewModel] over [FakePiSessionRepository]: preview polling (only while collected and
 * connected, ≈3 fps, backed off on refusals), the 404/503 phases, capture settings and replays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val piSession = FakePiSessionRepository()
    private lateinit var viewModel: CameraViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = CameraViewModel(piSession)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runCameraTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test
    fun thePhasesFollowThePreviewAnswers() {
        val on = PiFeatureAvailability.Available

        fun phase(
            outcome: CameraViewModel.PreviewOutcome?,
            settings: CameraCaptureSettings? = null,
            availability: PiFeatureAvailability = on,
        ) = CameraViewModel.buildState(availability, settings, outcome, emptyList(), CameraReplayState.Idle).phase

        assertThat(phase(null, availability = PiFeatureAvailability.Unavailable("Requires Wi-Fi")))
            .isEqualTo(CameraPhase.OFFLINE)
        assertThat(phase(null)).isEqualTo(CameraPhase.LOADING)
        assertThat(phase(null, CameraCaptureSettings(available = false))).isEqualTo(CameraPhase.NOT_ENABLED)
        assertThat(phase(answer(CameraPreview.Frame(byteArrayOf(1))))).isEqualTo(CameraPhase.LIVE)
        assertThat(phase(answer(CameraPreview.CaptureNotEnabled))).isEqualTo(CameraPhase.NOT_ENABLED)
        assertThat(phase(answer(CameraPreview.CameraNotRunning))).isEqualTo(CameraPhase.NOT_RUNNING)
        assertThat(phase(answer(CameraPreview.Unavailable(500, "boom")))).isEqualTo(CameraPhase.ERROR)
        assertThat(phase(CameraViewModel.PreviewOutcome.Failed("timeout"))).isEqualTo(CameraPhase.ERROR)
    }

    @Test
    fun theStillsArePolledAtAboutThreeFramesASecondWhileCollected() =
        runCameraTest {
            var still: Byte = 0
            piSession.previewResponse = { CameraPreview.Frame(byteArrayOf(still++)) }

            viewModel.frames.test {
                assertThat(awaitItem().toList()).containsExactly(0.toByte())
                advanceTimeBy(CameraViewModel.PREVIEW_INTERVAL_MILLIS)
                assertThat(awaitItem().toList()).containsExactly(1.toByte())
                advanceTimeBy(CameraViewModel.PREVIEW_INTERVAL_MILLIS)
                assertThat(awaitItem().toList()).containsExactly(2.toByte())
                cancelAndIgnoreRemainingEvents()
            }
            val calls = piSession.previewCalls

            // Not visible any more: no more requests.
            advanceTimeBy(10 * CameraViewModel.PREVIEW_INTERVAL_MILLIS)
            runCurrent()
            assertThat(piSession.previewCalls).isEqualTo(calls)
        }

    @Test
    fun nothingIsPolledUntilTheScreenCollectsNorWhileTheLinkIsDown() =
        runCameraTest {
            advanceTimeBy(5_000)
            assertThat(piSession.previewCalls).isEqualTo(0)

            piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "closed")
            viewModel.frames.test {
                advanceTimeBy(5_000)
                assertThat(piSession.previewCalls).isEqualTo(0)

                piSession.linkState.value = PiLinkState.Connected
                runCurrent()
                assertThat(piSession.previewCalls).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aPiWithoutCaptureShowsNotEnabledAndIsPolledSlowly() =
        runCameraTest {
            piSession.previewResponse = { CameraPreview.CaptureNotEnabled }

            viewModel.uiState.test {
                viewModel.frames.test {
                    advanceTimeBy(CameraViewModel.RETRY_INTERVAL_MILLIS - 1)
                    runCurrent()
                    assertThat(piSession.previewCalls).isEqualTo(1)
                    cancelAndIgnoreRemainingEvents()
                }
                assertThat(awaitUntil { it.phase == CameraPhase.NOT_ENABLED }.previewError).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aStoppedCameraAndFailuresShowTheirPhase() =
        runCameraTest {
            piSession.previewResponse = { CameraPreview.CameraNotRunning }

            viewModel.uiState.test {
                viewModel.frames.test {
                    runCurrent()
                    piSession.previewResponse = {
                        throw WifiOnlyFeatureException(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
                    }
                    advanceTimeBy(CameraViewModel.RETRY_INTERVAL_MILLIS)
                    runCurrent()
                    cancelAndIgnoreRemainingEvents()
                }
                awaitUntil { it.phase == CameraPhase.NOT_RUNNING }
                val failed = awaitUntil { it.phase == CameraPhase.ERROR }
                assertThat(failed.previewError).isEqualTo(WifiOnlyFeatureException.Reason.NOT_CONNECTED.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theCaptureSettingsAndReplayableShotsAreShown() =
        runCameraTest {
            piSession.cameraCaptureSettings.value =
                CameraCaptureSettings(available = true, running = true, width = 1456, height = 1088, fps = 240.0)
            piSession.setSession(
                listOf(
                    shot(3, replay = CameraReplay(id = "r3", displayMirrorHorizontal = true)),
                    shot(2, replay = null),
                    shot(1, replay = CameraReplay(id = "r1")),
                ),
            )

            viewModel.uiState.test {
                val state = awaitUntil { it.replays.size == 2 }
                assertThat(state.captureSummary).isEqualTo("1456×1088 @ 240 fps")
                assertThat(state.replays.map { it.replayId }).containsExactly("r3", "r1")
                assertThat(state.replays.first().mirrorHorizontal).isEqualTo(true)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aReplayIsPreparedThenReadyForTheNativePlayer() =
        runCameraTest {
            piSession.setSession(listOf(shot(3, replay = CameraReplay(id = "r3", displayMirrorHorizontal = true))))

            viewModel.uiState.test {
                awaitUntil { it.replays.isNotEmpty() }
                viewModel.onEvent(CameraEvent.PlayReplay("r3"))

                assertThat(awaitUntil { it.replay is CameraReplayState.Preparing }.replay)
                    .isEqualTo(CameraReplayState.Preparing("r3"))
                assertThat(awaitUntil { it.replay is CameraReplayState.Ready }.replay).isEqualTo(
                    CameraReplayState.Ready("r3", "http://pi.local:8080/api/camera/replays/r3/video", true),
                )
                assertThat(piSession.commands).containsExactly("prepare_replay:r3")

                viewModel.onEvent(CameraEvent.DismissReplay)
                assertThat(awaitUntil { it.replay == CameraReplayState.Idle }.replay).isEqualTo(CameraReplayState.Idle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aReplayThatCannotBePreparedShowsWhy() =
        runCameraTest {
            piSession.replayResponse = { error("Camera replay was not found") }

            viewModel.uiState.test {
                viewModel.onEvent(CameraEvent.PlayReplay("gone"))

                assertThat(awaitUntil { it.replay is CameraReplayState.Failed }.replay)
                    .isEqualTo(CameraReplayState.Failed("gone", "Camera replay was not found"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun refreshingTheSettingsAsksThePiAndAFailureBecomesAMessage() =
        runCameraTest {
            viewModel.onEvent(CameraEvent.RefreshSettings)
            runCurrent()
            assertThat(piSession.commands).containsExactly("get_camera_capture_settings")

            piSession.linkState.value = PiLinkState.WifiOnly
            viewModel.effects.test {
                viewModel.onEvent(CameraEvent.RefreshSettings)
                assertThat(awaitItem()).isEqualTo(
                    CameraEffect.Message(WifiOnlyFeatureException.Reason.BLUETOOTH.message),
                )
            }
        }

    private fun answer(preview: CameraPreview) = CameraViewModel.PreviewOutcome.Answer(preview)

    private fun shot(
        number: Int,
        replay: CameraReplay?,
    ) = ShotDetail(
        timestamp = "2026-09-25T10:0$number:00.000001",
        shotNumber = number,
        club = "driver",
        ballSpeedMph = 150.0,
        cameraReplay = replay,
    )

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
