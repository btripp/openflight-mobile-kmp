// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SimStatus
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeScreenReaderMonitor
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository()
    private val piSession = FakePiSessionRepository()
    private val speech = FakeSpeechEngine()
    private val screenReader = FakeScreenReaderMonitor()
    private lateinit var viewModel: SettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel =
            SettingsViewModel(
                shots,
                settings,
                piSession,
                AppLifecycle().apply { onForeground() },
                speech,
                screenReader,
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theStateShowsTheTransportLinkAndUnits() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.host == "pi.local:8080" && it.linkState == PiLinkState.Connected }

                assertThat(state.transport).isEqualTo(TransportType.WIFI)
                assertThat(state.linkDescription).isEqualTo("Connected")
                assertThat(state.units).isEqualTo(UnitSystem.IMPERIAL)

                viewModel.onEvent(SettingsEvent.SetUnits(UnitSystem.METRIC))
                assertThat(awaitUntil { it.units == UnitSystem.METRIC }.units).isEqualTo(UnitSystem.METRIC)
            }
        }

    @Test
    fun everyWifiOnlyActionIsDisabledWithTheReasonWhenTheLinkIsDown() =
        runTest {
            piSession.linkState.value = PiLinkState.WifiOnly
            piSession.radarConfig.value =
                RadarConfig(minSpeed = 10, maxSpeed = 0, minMagnitude = 300, transmitPower = 0)

            viewModel.uiState.testIgnoringRest {
                val ble = awaitUntil { it.linkState == PiLinkState.WifiOnly && it.radar.sliders.isNotEmpty() }
                val requiresWifi = PiFeatureAvailability.Unavailable("Requires Network")
                assertThat(ble.link).isEqualTo(requiresWifi)
                assertThat(ble.debug.toggle).isEqualTo(requiresWifi)
                assertThat(ble.cloud.upload).isEqualTo(requiresWifi)
                assertThat(ble.shutdown.shutdown).isEqualTo(requiresWifi)
                assertThat(ble.radar.refresh).isEqualTo(requiresWifi)
                assertThat(
                    ble.radar.sliders
                        .map { it.availability }
                        .distinct(),
                ).containsExactly(requiresWifi)

                piSession.linkState.value = PiLinkState.Reconnecting(2, 2_000, "closed")
                val down = awaitUntil { it.linkState is PiLinkState.Reconnecting }
                assertThat(down.link.disabledReason).isEqualTo("Not connected")
                assertThat(down.linkDescription).isEqualTo("Reconnecting: closed")
            }
        }

    @Test
    fun simulatorConnectorsShowTheirSeverityAndDetail() =
        runTest {
            piSession.simState.value =
                SimState(
                    connectors =
                        mapOf(
                            "gspro" to
                                SimStatus(
                                    "gspro",
                                    "reconnecting",
                                    host = "10.0.0.5",
                                    port = 921,
                                    attempt = 3,
                                    nextRetryInS = 5.0,
                                ),
                            "opengolfsim" to
                                SimStatus("opengolfsim", "error", host = "pc", port = 3111, message = "refused"),
                            "other" to SimStatus("other", "disabled"),
                        ),
                )

            viewModel.uiState.testIgnoringRest {
                val rows = awaitUntil { it.simulators.size == 3 }.simulators

                assertThat(rows[0]).isEqualTo(
                    SimulatorRow(
                        "gspro",
                        "GSPro",
                        "reconnecting",
                        SimSeverity.WARN,
                        "10.0.0.5:921 — retry in 5s (attempt 3)",
                    ),
                )
                assertThat(rows[1]).isEqualTo(
                    SimulatorRow("opengolfsim", "OpenGolfSim", "error", SimSeverity.ERROR, "pc:3111 — refused"),
                )
                assertThat(rows[2]).isEqualTo(SimulatorRow("other", "other", "disabled", SimSeverity.OFF, "disabled"))
            }
        }

    @Test
    fun radarSlidersFollowTheModeAndMockRules() =
        runTest {
            piSession.radarConfig.value =
                RadarConfig(minSpeed = 10, maxSpeed = 150, minMagnitude = 300, transmitPower = 2)
            piSession.mockMode.value = true
            piSession.triggerStatus.value = TriggerStatus(mode = "mock")

            viewModel.uiState.testIgnoringRest {
                val mock = awaitUntil { it.mockMode && it.radar.sliders.isNotEmpty() }.radar
                assertThat(mock.sliders.map { it.label }).containsExactly("Min Speed", "Min Magnitude", "TX Power")
                assertThat(mock.sliders[1].step).isEqualTo(50)
                assertThat(mock.sliders[1].max).isEqualTo(2000)
                assertThat(mock.sliders.map { it.availability.disabledReason }.distinct())
                    .containsExactly("Disabled in mock mode")
                assertThat(mock.tuningNotice).isEqualTo("Radar tuning disabled in mock mode")
                assertThat(mock.hint).isEqualTo("TX Power: 0 = max range, 7 = min range")

                piSession.triggerStatus.value = TriggerStatus(mode = "swing-speed")
                val swing = awaitUntil { it.radar.isSwingSpeedMode }.radar
                assertThat(swing.sliders.map { it.label }).containsExactly("Lower Speed", "Upper Speed", "TX Power")
                assertThat(swing.sliders[1].value).isEqualTo(150)
                assertThat(swing.sliders[0].availability).isEqualTo(PiFeatureAvailability.Available)
                assertThat(swing.sliders[2].availability.disabledReason).isEqualTo("Disabled in mock mode")
                assertThat(swing.tuningNotice).isEqualTo("Mock swing speed sliders shape simulated reps only")
            }
        }

    @Test
    fun releasingASliderSendsOnlyThatField() =
        runTest {
            viewModel.onEvent(SettingsEvent.SetRadarValue(RadarField.TRANSMIT_POWER, 5))
            viewModel.onEvent(SettingsEvent.RefreshRadarConfig)

            assertThat(piSession.commands).containsExactly(
                "set_radar_config:${RadarConfigUpdate(transmitPower = 5)}",
                "get_radar_config",
            )
        }

    @Test
    fun triggerDiagnosticsAreNewestFirstWithReadableReasons() =
        runTest {
            piSession.debugState.value =
                DebugState(
                    enabled = true,
                    logPath = "/home/pi/openflight_sessions/debug.jsonl",
                    triggerDiagnostics =
                        listOf(
                            TriggerDiagnostic(timestamp = "t1", accepted = false, reason = "no_outbound_speed"),
                            TriggerDiagnostic(timestamp = "t2", accepted = true, reason = "accepted"),
                            TriggerDiagnostic(timestamp = "t3", accepted = false, reason = "something_new"),
                        ),
                )

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.debug.enabled }

                assertThat(state.debug.logPath).isEqualTo("/home/pi/openflight_sessions/debug.jsonl")
                assertThat(state.radar.diagnostics.map { it.reasonText })
                    .containsExactly("something_new", "Shot detected", "No outbound speed >= 15 mph")
            }

            viewModel.onEvent(SettingsEvent.ToggleDebug)
            assertThat(piSession.commands).containsExactly("toggle_debug")
        }

    @Test
    fun cloudUploadIsDisabledWhileRunning() =
        runTest {
            viewModel.onEvent(SettingsEvent.UploadCloud)
            assertThat(piSession.commands).containsExactly("upload_cloud")

            piSession.cloudUploadStatus.value = CloudUploadStatus(CloudUploadState.RUNNING, "Uploading...")
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.cloud.state == CloudUploadState.RUNNING }
                assertThat(state.cloud.message).isEqualTo("Uploading...")
                assertThat(state.cloud.upload.disabledReason).isEqualTo("Uploading")
            }
        }

    @Test
    fun shutdownNeedsConfirmation() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SettingsEvent.RequestShutdown)
                assertThat(awaitUntil { it.shutdown.confirmationRequired }.shutdown.phase)
                    .isEqualTo(ShutdownPhase.Confirming)
                assertThat(shots.shutdownTargets).isEmpty()

                viewModel.onEvent(SettingsEvent.CancelShutdown)
                awaitUntil { !it.shutdown.confirmationRequired }
                viewModel.onEvent(SettingsEvent.ConfirmShutdown) // Nothing to confirm any more.
                assertThat(shots.shutdownTargets).isEmpty()

                viewModel.onEvent(SettingsEvent.RequestShutdown)
                awaitUntil { it.shutdown.confirmationRequired }
                viewModel.onEvent(SettingsEvent.ConfirmShutdown)
                assertThat(awaitUntil { it.shutdown.phase is ShutdownPhase.Done }.shutdown.phase)
                    .isEqualTo(ShutdownPhase.Done("pi.local:8080"))
                // Over HTTP (`POST /api/shutdown`), not the Socket.IO event.
                assertThat(shots.shutdownTargets).containsExactly("pi.local:8080")
                assertThat(piSession.commands).isEmpty()
            }
        }

    @Test
    fun shutdownWithoutALinkSaysWhyInsteadOfAskingForConfirmation() =
        runTest {
            piSession.linkState.value = PiLinkState.WifiOnly
            viewModel.effects.test {
                viewModel.onEvent(SettingsEvent.RequestShutdown)

                assertThat(awaitItem()).isEqualTo(SettingsEffect.Message("Requires Network"))
            }
            assertThat(viewModel.uiState.value.shutdown.confirmationRequired).isFalse()
        }

    @Test
    fun piNoticesAndFailedCommandsBecomeMessages() =
        runTest {
            viewModel.effects.test {
                piSession.notices.emit(PiNotice.RadarConfigFailed("Radar not connected"))
                assertThat(awaitItem()).isEqualTo(SettingsEffect.Message("Radar not connected"))

                piSession.notices.emit(PiNotice.SimSendFailed("gspro", "timeout"))
                assertThat(awaitItem()).isEqualTo(SettingsEffect.Message("gspro: timeout"))

                piSession.notices.emit(PiNotice.TrainingImplementFailed("Unknown")) // The training screen's.
                piSession.linkState.value = PiLinkState.Connecting
                viewModel.onEvent(SettingsEvent.ToggleDebug)
                assertThat(awaitItem()).isEqualTo(SettingsEffect.Message("Not connected to the Pi's live session yet."))
            }
        }

    @Test
    fun withoutARadarConfigThereAreNoSliders() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.linkState == PiLinkState.Connected }
                assertThat(state.radar.config).isNull()
                assertThat(state.radar.sliders).isEmpty()
            }
        }

    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    @Test
    fun theCalloutSectionDefaultsMatchSettingsRepositoryDefaults() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.linkState == PiLinkState.Connected }
                assertThat(state.callouts.enabled).isFalse()
                assertThat(
                    state.callouts.fields
                        .filter { it.selected }
                        .map { it.field },
                ).containsExactly(CalloutField.CARRY, CalloutField.BALL_SPEED)
                assertThat(state.callouts.previewText).isEqualTo("Carry 152 yards, Ball speed 118 miles per hour")
            }
        }

    @Test
    fun enablingCalloutsPersistsThroughTheRepository() =
        runTest {
            viewModel.onEvent(SettingsEvent.SetCalloutsEnabled(true))

            assertThat(settings.calloutsEnabled.value).isTrue()
        }

    @Test
    fun togglingAFieldOnAppendsItToTheSelectedList() =
        runTest {
            viewModel.onEvent(SettingsEvent.ToggleCalloutField(CalloutField.SMASH))

            assertThat(settings.calloutFields.value)
                .containsExactly(CalloutField.CARRY, CalloutField.BALL_SPEED, CalloutField.SMASH)
        }

    @Test
    fun togglingASelectedFieldOffRemovesIt() =
        runTest {
            viewModel.onEvent(SettingsEvent.ToggleCalloutField(CalloutField.CARRY))

            assertThat(settings.calloutFields.value).containsExactly(CalloutField.BALL_SPEED)
        }

    @Test
    fun movingTheSecondFieldUpSwapsItWithTheFirst() =
        runTest {
            viewModel.onEvent(SettingsEvent.MoveCalloutField(CalloutField.BALL_SPEED, up = true))

            assertThat(settings.calloutFields.value).containsExactly(CalloutField.BALL_SPEED, CalloutField.CARRY)
        }

    @Test
    fun previewSpeaksTheCurrentLivePreviewSentence() =
        runTest {
            viewModel.onEvent(SettingsEvent.PreviewCallout)

            assertThat(speech.spoken.map { it.text }).containsExactly("Carry 152 yards, Ball speed 118 miles per hour")
        }

    @Test
    fun previewStaysSilentWhileAScreenReaderIsActive() =
        runTest {
            screenReader.isActive.value = true

            viewModel.onEvent(SettingsEvent.PreviewCallout)

            assertThat(speech.spoken).isEmpty()
        }

    // Plan F8a2a: the range theme, added at the end to keep this file's diff mergeable (§4a A7).

    @Test
    fun theRangeThemeDefaultsToDayAndOffersEveryThemeInOrder() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.host == "pi.local:8080" }

                assertThat(state.rangeTheme.selected).isEqualTo(RangeThemeSetting.DAY)
                assertThat(state.rangeTheme.options.map { it.theme }).containsExactly(
                    RangeThemeSetting.DAY,
                    RangeThemeSetting.DUSK,
                    RangeThemeSetting.NIGHT,
                    RangeThemeSetting.LINKS,
                )
                assertThat(state.rangeTheme.options.map { it.label }).containsExactly("Day", "Dusk", "Night", "Links")
            }
        }

    @Test
    fun pickingARangeThemePersistsItAndShowsIt() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SettingsEvent.SetRangeTheme(RangeThemeSetting.NIGHT))

                assertThat(awaitUntil { it.rangeTheme.selected == RangeThemeSetting.NIGHT }.rangeTheme.selected)
                    .isEqualTo(RangeThemeSetting.NIGHT)
                assertThat(settings.rangeTheme.value).isEqualTo(RangeThemeSetting.NIGHT)
            }
        }

    // Plan F8a2t: the shot trail, added at the end to keep this file's diff mergeable (§4a A7).

    @Test
    fun theShotTrailDefaultsToClassicAndOffersEveryStyleInOrder() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val trail = awaitUntil { it.host == "pi.local:8080" }.shotTrail

                assertThat(trail.selected).isEqualTo(ShotTrailStyle.CLASSIC)
                assertThat(trail.keepLastLabel).isEqualTo("Off")
                assertThat(trail.landingEffectLabel).isEqualTo("Off")
                assertThat(trail.styles.map { it.style }).isEqualTo(ShotTrailStyle.entries.toList())
                assertThat(trail.styles.map { it.label }).containsExactly(
                    "Classic",
                    "Broadcast glow",
                    "Comet",
                    "Club colour",
                    "Dotted",
                    "Smoke",
                    "Neon",
                    "Speed heat",
                    "Rainbow",
                    "Spin ribbon",
                    "Ground track",
                )
                assertThat(trail.keepOptions.map { it.label }).containsExactly("Off", "Last 3", "Last 5", "Last 10")
                assertThat(trail.landingEffects.map { it.label }).containsExactly("Off", "Ring", "Burst")
            }
        }

    @Test
    fun pickingTheShotTrailOptionsPersistsThemAndShowsThem() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SettingsEvent.SetShotTrail(ShotTrailStyle.SMOKE))
                viewModel.onEvent(SettingsEvent.SetShotTrailKeepLast(3))
                viewModel.onEvent(SettingsEvent.SetLandingEffect(LandingEffect.RING))

                val trail =
                    awaitUntil {
                        it.shotTrail.selected == ShotTrailStyle.SMOKE &&
                            it.shotTrail.keepLast == 3 &&
                            it.shotTrail.landingEffect == LandingEffect.RING
                    }.shotTrail
                assertThat(trail.selectedLabel).isEqualTo("Smoke")
                assertThat(trail.keepLastLabel).isEqualTo("Last 3")
                assertThat(settings.shotTrail.value).isEqualTo(ShotTrailStyle.SMOKE)
                assertThat(settings.shotTrailKeepLast.value).isEqualTo(3)
                assertThat(settings.landingEffect.value).isEqualTo(LandingEffect.RING)
            }
        }

    @Test
    fun showTotalDistanceIsOnByDefaultAndTheToggleSharesTheRangesSetting() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitItem().showTotalDistance).isTrue()

                viewModel.onEvent(SettingsEvent.SetShowTotalDistance(false))
                awaitUntil { !it.showTotalDistance }
                assertThat(settings.showTotalDistance.value).isFalse()

                // The range's quick settings write the same key; Settings follows it.
                settings.setShowTotalDistance(true)
                awaitUntil { it.showTotalDistance }
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
