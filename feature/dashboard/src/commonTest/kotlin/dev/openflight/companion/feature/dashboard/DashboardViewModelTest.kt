// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
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
    private lateinit var viewModel: DashboardViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = DashboardViewModel(shots, settings)
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
