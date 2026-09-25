// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.SpinSource
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.CompletableDeferred
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
class DashboardViewModelTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()
    private lateinit var viewModel: DashboardViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = DashboardViewModel(shots, settings, piSession)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withoutShotsTheStateIsWaitingAndShowsTheSavedSettings() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.connection.hostText == "pi.local:8080" }
                assertThat(state).isInstanceOf<DashboardUiState.Waiting>()
                assertThat(state.connection.transport).isEqualTo(TransportType.WIFI)
                assertThat(state.connection.club).isEqualTo(GolfClub.DRIVER)
                assertThat(state.connection.showHostField).isTrue()
            }
        }

    @Test
    fun shotsMakeTheStateLiveWithTheNewestShotFirst() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.history.value = listOf(shot(3), shot(2), shot(1))
                val state = awaitUntil { it is DashboardUiState.Live } as DashboardUiState.Live
                assertThat(state.latest).isEqualTo(shot(3))
                assertThat(state.previous).containsExactly(shot(2), shot(1))
            }
        }

    @Test
    fun theClubMenuIsEnabledOnlyWhileConnected() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                for (state in listOf(ConnectionState.Idle, ConnectionState.Scanning, ConnectionState.Error("x"))) {
                    shots.connectionState.value = state
                    assertThat(awaitUntil { it.connection.state == state }.connection.clubMenuEnabled).isFalse()
                }
                shots.connectionState.value = ConnectionState.Connected
                assertThat(awaitUntil { it.connection.state == ConnectionState.Connected }.connection.clubMenuEnabled)
                    .isTrue()
            }
        }

    @Test
    fun retryShowsForRetryableStatesAndTheSpinnerWhileConnecting() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.connectionState.value = ConnectionState.Unavailable("Bluetooth is off")
                val unavailable = awaitUntil { it.connection.state is ConnectionState.Unavailable }.connection
                assertThat(unavailable.showRetry).isTrue()
                assertThat(unavailable.showProgress).isFalse()

                shots.connectionState.value = ConnectionState.Connecting
                val connecting = awaitUntil { it.connection.state == ConnectionState.Connecting }.connection
                assertThat(connecting.showRetry).isFalse()
                assertThat(connecting.showProgress).isTrue()

                shots.connectionState.value = ConnectionState.Connected
                val connected = awaitUntil { it.connection.state == ConnectionState.Connected }.connection
                assertThat(connected.showRetry).isFalse()
                assertThat(connected.showProgress).isFalse()
                assertThat(connected.statusTitle).isEqualTo("OpenFlight Pi")
            }
        }

    @Test
    fun editingTheHostChangesOnlyTheFieldText() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(DashboardEvent.HostEdited("10.0.2.2:80"))
                assertThat(awaitUntil { it.connection.hostText == "10.0.2.2:80" }.connection.hostText)
                    .isEqualTo("10.0.2.2:80")
                assertThat(settings.hostWrites).containsExactly()
                assertThat(shots.retryCount).isEqualTo(0)
            }
        }

    @Test
    fun submittingANewHostPersistsItTrimmed() =
        runTest {
            viewModel.onEvent(DashboardEvent.HostEdited("  10.0.2.2:8091 "))
            viewModel.onEvent(DashboardEvent.HostSubmitted)

            assertThat(settings.hostWrites).containsExactly("10.0.2.2:8091")
            assertThat(shots.retryCount).isEqualTo(0)
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { it.connection.hostText == "10.0.2.2:8091" }.connection.hostText)
                    .isEqualTo("10.0.2.2:8091")
            }
        }

    @Test
    fun submittingTheSavedHostRetries() =
        runTest {
            viewModel.onEvent(DashboardEvent.HostSubmitted)

            assertThat(settings.hostWrites).containsExactly()
            assertThat(shots.retryCount).isEqualTo(1)
        }

    @Test
    fun retryAndTransportChangesGoToTheRepositories() =
        runTest {
            viewModel.onEvent(DashboardEvent.Retry)
            viewModel.onEvent(DashboardEvent.TransportChanged(TransportType.BLUETOOTH))

            assertThat(shots.retryCount).isEqualTo(1)
            assertThat(settings.transport.value).isEqualTo(TransportType.BLUETOOTH)
            viewModel.selectedTransport.testIgnoringRest {
                assertThat(awaitUntil { it != null }).isEqualTo(TransportType.BLUETOOTH)
            }
        }

    @Test
    fun aClubChangeDisablesTheMenuUntilThePiConfirms() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val response = CompletableDeferred<ClubSelection>()
            shots.setClubResponse = { response.await() }

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(DashboardEvent.ClubSelected(GolfClub.IRON_7))
                val changing = awaitUntil { it.connection.isChangingClub }.connection
                assertThat(changing.clubMenuEnabled).isFalse()

                // A second pick while one is in flight is ignored.
                viewModel.onEvent(DashboardEvent.ClubSelected(GolfClub.SAND_WEDGE))
                assertThat(shots.setClubCalls).containsExactly(GolfClub.IRON_7)

                response.complete(ClubSelection(status = "ok", club = GolfClub.IRON_7))
                val done = awaitUntil { !it.connection.isChangingClub && it.connection.club == GolfClub.IRON_7 }
                assertThat(done.connection.clubMenuEnabled).isTrue()
                assertThat(done.connection.clubError).isNull()
            }
        }

    @Test
    fun aFailedClubChangeShowsTheErrorUntilDismissed() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            shots.setClubResponse = { error("OpenFlight is busy") }

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(DashboardEvent.ClubSelected(GolfClub.IRON_7))
                val failed = awaitUntil { it.connection.clubError != null }.connection
                assertThat(failed.clubError).isEqualTo("OpenFlight is busy")
                assertThat(failed.club).isEqualTo(GolfClub.DRIVER)
                assertThat(failed.clubMenuEnabled).isTrue()

                viewModel.onEvent(DashboardEvent.DismissError)
                assertThat(awaitUntil { it.connection.clubError == null }.connection.clubError).isNull()
            }
        }

    @Test
    fun aClubPushedByThePiUpdatesTheMenu() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                settings.selectedClub.value = GolfClub.PITCHING_WEDGE
                assertThat(awaitUntil { it.connection.club == GolfClub.PITCHING_WEDGE }.connection.club)
                    .isEqualTo(GolfClub.PITCHING_WEDGE)
            }
        }

    @Test
    fun theUiStateCarriesTheSavedUnitPreference() =
        runTest {
            settings.units.value = UnitSystem.METRIC
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { it.units == UnitSystem.METRIC }.units).isEqualTo(UnitSystem.METRIC)
            }
        }

    @Test
    fun clubStatsAndChipsAreComputedOverTheFullHistory() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.history.value = listOf(shot(1, club = "7-iron"), shot(2, club = "driver"))
                val state = awaitUntil { it.clubStats.shotCount == 2 }
                assertThat(state.clubStats.shotCount).isEqualTo(2)
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
            }
        }

    @Test
    fun aFreshShotFiresTheNewShotEffect() =
        runTest {
            viewModel.effects.test {
                shots.history.value = listOf(shot(1))
                assertThat(awaitItem()).isEqualTo(DashboardEffect.NewShot)

                shots.history.value = listOf(shot(2), shot(1))
                assertThat(awaitItem()).isEqualTo(DashboardEffect.NewShot)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun historyAlreadyPresentWhenTheViewModelStartsDoesNotFireTheEffect() =
        runTest {
            val preSeededSettings = FakeSettingsRepository()
            val preSeededShots = FakeShotRepository(preSeededSettings)
            preSeededShots.history.value = listOf(shot(1))
            val freshViewModel = DashboardViewModel(preSeededShots, preSeededSettings, FakePiSessionRepository())

            freshViewModel.effects.test {
                expectNoEvents()
            }
        }

    @Test
    fun aReplayedShotDoesNotFireTheEffectBecauseHistoryDoesNotChange() =
        runTest {
            viewModel.effects.test {
                shots.history.value = listOf(shot(1))
                assertThat(awaitItem()).isEqualTo(DashboardEffect.NewShot)

                // The repository's eventId-deduplicated history is unchanged by a replay (plan §0.3):
                // setting it to an equal list emits no new StateFlow value, so no second effect fires.
                shots.history.value = listOf(shot(1))
                expectNoEvents()
            }
        }

    // region Pi enrichment (plan R6b)

    @Test
    fun shotsTheSessionKnowsAreEnrichedByTimestamp() =
        runTest {
            val latest = shot(2).copy(timestamp = "2026-09-24T15:38:33.264795")
            val previous = shot(1).copy(timestamp = "2026-09-24T15:30:00.000001")
            piSession.setSession(
                listOf(
                    ShotDetail(
                        timestamp = latest.timestamp,
                        launchAngleVertical = 15.4,
                        launchAngleConfidence = 0.72,
                        spinRpm = 2836.0,
                        spinQuality = "medium",
                        spinSource = "calculated",
                        carryRange = listOf(231.0, 255.0),
                        carrySpinAdjusted = 248.0,
                        profileName = "Ann",
                    ),
                ),
            )

            viewModel.uiState.testIgnoringRest {
                shots.history.value = listOf(latest, previous)
                val state = awaitUntil { it is DashboardUiState.Live } as DashboardUiState.Live

                val enrichment = state.latestEnrichment!!
                assertThat(enrichment.launchAngleConfidence).isEqualTo(ConfidenceLevel.HIGH)
                assertThat(enrichment.launchAngleConfidence?.filledDots).isEqualTo(3)
                assertThat(enrichment.spinQuality).isEqualTo(ConfidenceLevel.MEDIUM)
                assertThat(enrichment.spinSource).isEqualTo(SpinSource.ESTIMATED)
                assertThat(enrichment.carryRangeText(UnitSystem.IMPERIAL)).isEqualTo("231-255 yds")
                assertThat(enrichment.carrySpinAdjustedYards).isEqualTo(248.0)
                assertThat(enrichment.profileName).isEqualTo("Ann")
                // The Pi never reported the previous shot: no detail for that row.
                assertThat(state.enrichmentFor(previous)).isNull()
            }
        }

    @Test
    fun detailArrivingLaterEnrichesTheShotAlreadyShown() =
        runTest {
            val latest = shot(1).copy(timestamp = "2026-09-24T15:38:33.264795")
            viewModel.uiState.testIgnoringRest {
                shots.history.value = listOf(latest)
                awaitUntil { it is DashboardUiState.Live && it.latestEnrichment == null }

                piSession.setSession(listOf(ShotDetail(timestamp = latest.timestamp, profileName = "Ann")))

                val state = awaitUntil { (it as? DashboardUiState.Live)?.latestEnrichment != null }
                assertThat((state as DashboardUiState.Live).latestEnrichment?.profileName).isEqualTo("Ann")
            }
        }

    @Test
    fun withoutPiDetailTheShotsHaveNoEnrichment() =
        runTest {
            val bluetooth = FakePiSessionRepository(PiLinkState.WifiOnly)
            val bleViewModel = DashboardViewModel(shots, settings, bluetooth)

            bleViewModel.uiState.testIgnoringRest {
                shots.history.value = listOf(shot(2), shot(1))
                val state = awaitUntil { it is DashboardUiState.Live } as DashboardUiState.Live

                assertThat(state.enrichments).isEmpty()
                assertThat(state.latestEnrichment).isNull()
            }
        }

    // endregion

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
