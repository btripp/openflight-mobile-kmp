// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakePiSessionRepository
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

/**
 * Plan R8d on the connection card: help links (ported from the Expo `openflight-docs-link`
 * `ConnectionBar.test.tsx`), tap-to-fill host hints, the Local Network denial and the
 * once-per-launch club confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardConnectionCardTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository(PiLinkState.Idle)
    private val confirmation = ClubConfirmation()

    private fun viewModel() = DashboardViewModel(shots, settings, piSession, confirmation)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region help links

    @Test
    fun aFirstTimeUserIsPointedAtTheBuildGuide() {
        val link = ConnectionPanelState(state = ConnectionState.Idle).helpLink
        assertThat(link).isEqualTo(ConnectionHelpLink.BUILD_GUIDE)
        assertThat(link?.url).isEqualTo("https://open-flight.github.io/openflight/get-started/")
        assertThat(ConnectionPanelState(state = ConnectionState.Connecting).helpLink)
            .isEqualTo(ConnectionHelpLink.BUILD_GUIDE)
    }

    @Test
    fun aFailedConnectionIsPointedAtTroubleshootingInstead() {
        val link = ConnectionPanelState(state = ConnectionState.Error("refused")).helpLink
        assertThat(link).isEqualTo(ConnectionHelpLink.TROUBLESHOOTING)
        assertThat(link?.url).isEqualTo("https://open-flight.github.io/openflight/troubleshooting/")
    }

    @Test
    fun theHelpLinkHidesOnceConnected() {
        assertThat(ConnectionPanelState(state = ConnectionState.Connected).helpLink).isNull()
        // The Pi's live session is up even though its SSE stream isn't (current backend).
        assertThat(ConnectionPanelState(state = ConnectionState.Error("404"), piLinkConnected = true).helpLink)
            .isNull()
    }

    @Test
    fun theHelpLinkFollowsTheLiveState() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                awaitUntil { it.connection.helpLink == ConnectionHelpLink.BUILD_GUIDE }
                shots.connectionState.value = ConnectionState.Error("refused")
                awaitUntil { it.connection.helpLink == ConnectionHelpLink.TROUBLESHOOTING }
                piSession.linkState.value = PiLinkState.Connected
                awaitUntil { it.connection.helpLink == null }
            }
        }

    // endregion

    // region host hints

    @Test
    fun wifiOffersTheAccessPointAndHomeNetworkHints() {
        val hints = ConnectionPanelState(transport = TransportType.WIFI).hostHints.map { it.host }
        assertThat(hints).containsExactly("192.168.4.1:8080", "192.168.1.100:8080")
        assertThat(ConnectionPanelState(transport = TransportType.BLUETOOTH).hostHints).isEmpty()
    }

    @Test
    fun aHintFillsTheFieldWithoutConnecting() =
        runTest {
            viewModel().let { vm ->
                vm.uiState.testIgnoringRest {
                    vm.onEvent(DashboardEvent.HostHintSelected("192.168.4.1:8080"))
                    awaitUntil { it.connection.hostText == "192.168.4.1:8080" }
                }
            }
            assertThat(settings.hostWrites).isEmpty()
        }

    @Test
    fun theDefaultPrefillIsStillRaspberrypiLocal() {
        assertThat(ConnectionPanelState().hostText).isEqualTo("raspberrypi.local:8080")
    }

    // endregion

    // region local network

    @Test
    fun aDeniedLocalNetworkOnTheStreamIsFlagged() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                shots.connectionState.value =
                    ConnectionState.Error("denied", ConnectionErrorKind.LOCAL_NETWORK_DENIED)
                awaitUntil { it.connection.localNetworkDenied }
            }
        }

    @Test
    fun aDeniedLocalNetworkOnTheSocketIsFlagged() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "denied", localNetworkDenied = true)
                awaitUntil { it.connection.localNetworkDenied }
            }
        }

    @Test
    fun anOrdinaryErrorIsNotADenial() {
        assertThat(ConnectionPanelState(state = ConnectionState.Error("refused")).localNetworkDenied).isFalse()
    }

    // endregion

    // region club confirmation

    @Test
    fun theClubConfirmationWaitsForTheFirstConnection() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                assertThat(awaitItem().connection.showClubConfirmation).isFalse()
                shots.connectionState.value = ConnectionState.Connecting
                piSession.linkState.value = PiLinkState.Connecting
                assertThat(confirmation.phase.value).isEqualTo(ClubConfirmation.Phase.WAITING_FOR_CONNECTION)

                piSession.linkState.value = PiLinkState.Connected

                awaitUntil { it.connection.showClubConfirmation }
            }
        }

    @Test
    fun confirmingHidesItAndAReconnectDoesNotReopenIt() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                shots.connectionState.value = ConnectionState.Connected
                awaitUntil { it.connection.showClubConfirmation }

                vm.onEvent(DashboardEvent.ClubConfirmed)
                awaitUntil { !it.connection.showClubConfirmation }

                shots.connectionState.value = ConnectionState.Error("dropped")
                awaitUntil { it.connection.state is ConnectionState.Error }
                shots.connectionState.value = ConnectionState.Connected
                val reconnected = awaitUntil { it.connection.state == ConnectionState.Connected }
                assertThat(reconnected.connection.showClubConfirmation).isFalse()
                assertThat(confirmation.phase.value).isEqualTo(ClubConfirmation.Phase.DONE)
            }
        }

    @Test
    fun aNewDashboardInTheSameLaunchDoesNotAskAgain() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            viewModel().onEvent(DashboardEvent.ClubConfirmed)

            viewModel().uiState.testIgnoringRest {
                awaitUntil { it.connection.state == ConnectionState.Connected }
                assertThat(confirmation.phase.value).isEqualTo(ClubConfirmation.Phase.DONE)
            }
        }

    @Test
    fun pickingAClubAnswersTheConfirmation() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                shots.connectionState.value = ConnectionState.Connected
                awaitUntil { it.connection.showClubConfirmation }

                vm.onEvent(DashboardEvent.ClubSelected(GolfClub.IRON_7))

                awaitUntil { !it.connection.showClubConfirmation }
            }
            assertThat(shots.setClubCalls).containsExactly(GolfClub.IRON_7)
        }

    @Test
    fun theConfirmationIsInMemoryOnly() {
        // A new process (a new ClubConfirmation) asks again after its first connection.
        val next = ClubConfirmation()
        assertThat(next.phase.value).isEqualTo(ClubConfirmation.Phase.WAITING_FOR_CONNECTION)
        next.onConnected()
        next.onConnected()
        assertThat(next.phase.value).isEqualTo(ClubConfirmation.Phase.SHOWING)
        next.dismiss()
        next.onConnected()
        assertThat(next.phase.value).isEqualTo(ClubConfirmation.Phase.DONE)
    }

    // endregion

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
