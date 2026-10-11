// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.pi.PiUpdateNotice
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Tester report 2026-10-09: the dashboard suggests updating a Pi that predates catch-up. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardPiUpdateTest {
    private val settings = FakeSettingsRepository(transport = TransportType.BLUETOOTH)
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()

    private fun viewModel() = DashboardViewModel(shots, settings, piSession, ClubConfirmation())

    private val olderPi = PiUpdateNotice(missesShots = true, missesDeletions = true)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anUpToDatePiShowsNoNotice() =
        runTest {
            viewModel().uiState.test {
                assertThat(awaitItem().piUpdate).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun anOlderPiShowsTheNotice() =
        runTest {
            viewModel().uiState.test {
                shots.piUpdateNotice.value = olderPi
                val state = awaitUntil { it.piUpdate != null }

                assertThat(state).isInstanceOf<DashboardUiState.Waiting>()
                assertThat(state.piUpdate?.title).isEqualTo(PiUpdateNotice.TITLE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun dismissingHidesTheNoticeForThisLaunchEvenAcrossAReconnect() =
        runTest {
            val viewModel = viewModel()
            viewModel.uiState.test {
                shots.piUpdateNotice.value = olderPi
                awaitUntil { it.piUpdate != null }

                viewModel.onEvent(DashboardEvent.DismissPiUpdate)
                assertThat(awaitUntil { it.piUpdate == null }.piUpdate).isNull()

                // The link drops and the same Pi comes back: still dismissed.
                shots.piUpdateNotice.value = null
                shots.piUpdateNotice.value = olderPi
                expectNoEvents()
                assertThat(viewModel.uiState.value.piUpdate).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aDifferentNoticeAfterADismissalIsShown() =
        runTest {
            val viewModel = viewModel()
            viewModel.uiState.test {
                shots.piUpdateNotice.value = PiUpdateNotice(missesShots = false, missesDeletions = true)
                awaitUntil { it.piUpdate != null }
                viewModel.onEvent(DashboardEvent.DismissPiUpdate)
                awaitUntil { it.piUpdate == null }

                // Another, older Pi: it misses more, so the user hears about it.
                shots.piUpdateNotice.value = olderPi

                assertThat(awaitUntil { it.piUpdate != null }.piUpdate).isEqualTo(olderPi)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
