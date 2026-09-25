// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
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
class TrainingViewModelTest {
    private val piSession = FakePiSessionRepository()
    private val settings = FakeSettingsRepository()
    private lateinit var viewModel: TrainingViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = TrainingViewModel(piSession, settings)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun thePickerOffersEveryServerImplementGroupedLikeTheWebUiWithoutLegacyAliases() {
        val groups = TrainingImplements.groups

        assertThat(groups.map { it.name }).containsExactly("General", "SuperSpeed", "TheStack", "Rypstick")
        assertThat(groups[0].options.map { it.id }).containsExactly("driver", "custom")
        assertThat(groups[1].options.map { it.label })
            .containsExactly("SuperSpeed Light", "SuperSpeed Medium", "SuperSpeed Heavy")
        assertThat(groups[2].options.size).isEqualTo(13)
        assertThat(groups[3].options.last().label).isEqualTo("Rypstick 3 Weights + Counterweight")
        val offered = groups.flatMap { group -> group.options.map { it.id } }
        assertThat(offered).doesNotContain("speed-stick-light")
        // Every offered key is one the server accepts.
        assertThat(offered.all { it in TrainingImplement.KNOWN }).isTrue()
        assertThat(offered.size).isEqualTo(TrainingImplement.KNOWN.size - 3)
    }

    @Test
    fun lastBestAndAverageCoverTheActiveProfileAndImplement() =
        runTest {
            piSession.profiles.value =
                ProfilesState(
                    profiles = listOf(Profile(id = "ann", name = "Ann"), Profile(id = "bob", name = "Bob")),
                    activeProfileId = "ann",
                    loaded = true,
                )
            piSession.trainingImplement.value = TrainingImplement("stack-100g", "Stack 100g")
            piSession.triggerStatus.value = TriggerStatus(mode = "swing-speed")
            piSession.setSession(
                listOf(
                    rep(4, 88.0, profileId = "bob"),
                    rep(3, 95.0),
                    rep(2, 100.0),
                    rep(1, 90.0, implement = "driver"),
                ),
            )

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.hasSwings }

                assertThat(state.availability).isEqualTo(PiFeatureAvailability.Available)
                assertThat(state.isSwingSpeedMode).isTrue()
                assertThat(state.triggerMode).isEqualTo("swing-speed")
                assertThat(state.profileName).isEqualTo("Ann")
                assertThat(state.selectedImplement).isEqualTo(ImplementOption("stack-100g", "Stack 100g"))
                assertThat(
                    state.stats,
                ).isEqualTo(SwingSpeedStats(2, lastSpeedMph = 95.0, bestSpeedMph = 100.0, avgSpeedMph = 97.5))
                // The gauge shows the newest rep, whoever swung it.
                assertThat(state.lastRep?.speedMph).isEqualTo(88.0)
                assertThat(state.lastRep?.readingCount).isEqualTo(5)
            }
        }

    @Test
    fun selectingAnImplementSendsItAndShowsItUntilThePiConfirms() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(TrainingEvent.SelectImplement("rypstick-2w"))

                val pending = awaitUntil { it.selectedImplement.id == "rypstick-2w" }
                assertThat(pending.selectedImplement.label).isEqualTo("Rypstick 2 Weights")
                assertThat(piSession.commands).containsExactly("set_training_implement:rypstick-2w")

                piSession.trainingImplement.value = TrainingImplement("rypstick-2w", "Rypstick 2 Weights")
                // Confirmed: the pending value is dropped and the Pi's (identical) value shows.
                assertThat(viewModel.uiState.value.selectedImplement.id).isEqualTo("rypstick-2w")
            }
        }

    @Test
    fun aRejectedImplementRevertsAndShowsTheServerError() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(TrainingEvent.SelectImplement("stack-60g"))
                awaitUntil { it.selectedImplement.id == "stack-60g" }

                piSession.notices.emit(PiNotice.TrainingImplementFailed("Unknown training implement"))

                val state = awaitUntil { it.error != null }
                assertThat(state.error).isEqualTo("Unknown training implement")
                assertThat(state.selectedImplement.id).isEqualTo("driver")

                viewModel.onEvent(TrainingEvent.DismissError)
                assertThat(awaitUntil { it.error == null }.error).isNull()
            }
        }

    @Test
    fun onBluetoothEverythingIsDisabledWithTheReason() =
        runTest {
            piSession.linkState.value = PiLinkState.WifiOnly
            settings.transport.value = TransportType.BLUETOOTH

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.availability.disabledReason == "Requires Wi-Fi" }
                assertThat(state.availability.disabledReason).isEqualTo("Requires Wi-Fi")
                assertThat(state.hasSwings).isFalse()

                viewModel.onEvent(TrainingEvent.SelectImplement("stack"))

                val failed = awaitUntil { it.error != null }
                assertThat(failed.error?.startsWith("This feature needs the Pi over Wi-Fi")).isEqualTo(true)
                assertThat(failed.selectedImplement.id).isEqualTo("driver")
                assertThat(piSession.commands).isEmpty()
            }
        }

    @Test
    fun simulateSwingIsOfferedOnlyInMockMode() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { true }.showSimulateSwing).isFalse()
                piSession.mockMode.value = true
                awaitUntil { it.showSimulateSwing }
            }

            viewModel.onEvent(TrainingEvent.SimulateSwing)

            assertThat(piSession.commands).containsExactly("simulate_shot")
        }

    private fun rep(
        number: Int,
        speedMph: Double,
        profileId: String = "ann",
        implement: String = "stack-100g",
    ): ShotDetail =
        ShotDetail(
            timestamp = "2026-09-24T16:00:0$number.000001",
            ballSpeedMph = speedMph,
            clubSpeedMph = speedMph,
            club = "Swing Speed",
            profileId = profileId,
            mode = "swing-speed",
            swingSpeedReadingCount = 5,
            trainingImplement = implement,
            trainingImplementLabel = TrainingImplement.KNOWN[implement],
        )

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
