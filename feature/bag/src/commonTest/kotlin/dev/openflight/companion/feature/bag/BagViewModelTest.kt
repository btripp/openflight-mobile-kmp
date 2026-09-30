// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.BagRepository
import dev.openflight.companion.core.data.SessionSource
import dev.openflight.companion.core.insights.GapFlag
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import dev.openflight.companion.core.testing.FakeBagRepository
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BagViewModelTest {
    private val bags = FakeBagRepository()
    private val history = FakeShotHistoryRepository()
    private val conditions = FakeConditionsRepository()
    private val settings = FakeSettingsRepository()
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BagViewModel(bags, history, conditions, settings, computeDispatcher = dispatcher)

    @Test
    fun anEmptyBagIsSeededWithTheDefaultFourteen() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded && it.clubs.isNotEmpty() }
                assertThat(state.bagName).isEqualTo(BagRepository.DEFAULT_BAG_NAME)
                assertThat(state.clubs.map { it.club }).isEqualTo(BagRepository.DEFAULT_CLUBS)
                assertThat(state.clubs.first().carryLabel).isEqualTo("—")
                assertThat(state.addableClubs.size).isEqualTo(GolfClub.entries.size - 14)
            }
        }

    @Test
    fun rowsShowTheAverageCarryAndATightGapIsFlagged() =
        runTest {
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0) + fiveShots(GolfClub.IRON_8, 155.0))

            viewModel().uiState.testIgnoringRest {
                val state =
                    awaitUntil {
                        row(it, GolfClub.IRON_7)?.avgCarryYards != null &&
                            row(it, GolfClub.IRON_8)?.avgCarryYards != null
                    }
                val sevenIron = row(state, GolfClub.IRON_7)!!
                assertThat(sevenIron.avgCarryYards!!).isCloseTo(160.0, 1e-6)
                assertThat(sevenIron.carryLabel).isEqualTo("160 yds")
                assertThat(sevenIron.shotCountLabel).isEqualTo("5 shots")
                assertThat(sevenIron.gap?.flag).isEqualTo(GapFlag.TOO_TIGHT)
                assertThat(sevenIron.gap?.label).isEqualTo("5 yds gap · tight")
                assertThat(row(state, GolfClub.IRON_8)!!.gap).isNull()
                assertThat(state.carryAdjusted).isFalse()
            }
        }

    @Test
    fun theMetricCarrySpreadIsRoundedOnce() =
        runTest {
            // Five shots at 158..162 yds: a spread of 1.58 yds (1.45 m). Rounding the yards first
            // (2 yds = 1.83 m) would show "± 2 m".
            settings.units.value = UnitSystem.METRIC
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { row(it, GolfClub.IRON_7)?.plusMinusLabel != null }
                assertThat(row(state, GolfClub.IRON_7)!!.plusMinusLabel).isEqualTo("± 1 m")
            }
        }

    @Test
    fun importedSessionsDontCountUntilIncluded() =
        runTest {
            history.put("imported", fiveShots(GolfClub.DRIVER, 250.0, "imported"), source = SessionSource.IMPORTED)

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded && it.clubs.isNotEmpty() }
                assertThat(row(state, GolfClub.DRIVER)!!.avgCarryYards).isNull()
            }
        }

    @Test
    fun movingAClubUpSwapsItWithTheOneAbove() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { it.clubs.isNotEmpty() }
                vm.onEvent(BagEvent.MoveClub(state.clubs[1].id, up = true))
                val moved = awaitUntil { it.clubs.first().club == GolfClub.WOOD_3 }
                assertThat(moved.clubs.take(2).map { it.club }).containsExactly(GolfClub.WOOD_3, GolfClub.DRIVER)
            }
        }

    @Test
    fun addingAndRemovingClubsEditsTheActiveBag() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { it.clubs.isNotEmpty() }
                vm.onEvent(BagEvent.AddClub(GolfClub.WOOD_7))
                assertThat(awaitUntil { it.clubs.size == 15 }.clubs.last().club).isEqualTo(GolfClub.WOOD_7)
                vm.onEvent(BagEvent.RemoveClub(state.clubs.first().id))
                assertThat(awaitUntil { it.clubs.size == 14 }.clubs.first().club).isEqualTo(GolfClub.WOOD_3)
            }
        }

    @Test
    fun editingAClubStoresMakeModelAndLoft() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { it.clubs.isNotEmpty() }
                vm.onEvent(BagEvent.EditClub(state.clubs.first().id, make = "Acme", model = "Rocket", loft = "10.5"))
                val edited = awaitUntil { it.clubs.first().makeModel != null }.clubs.first()
                assertThat(edited.makeModel).isEqualTo("Acme Rocket")
                assertThat(edited.loftDeg).isEqualTo(10.5)
            }
        }

    @Test
    fun anImpossibleLoftIsAnErrorAndNothingIsStored() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { it.clubs.isNotEmpty() }
                vm.onEvent(BagEvent.EditClub(state.clubs.first().id, make = "", model = "", loft = "95"))
                assertThat(awaitUntil { it.error != null }.clubs.first().loftDeg).isNull()
            }
        }

    @Test
    fun savingConditionsStoresThemAsManualAndClosesTheEditor() =
        runTest {
            val vm = viewModel()
            vm.effects.test {
                vm.onEvent(
                    BagEvent.SaveConditions(
                        ConditionsForm(
                            altitude = "5280",
                            temperature = "59",
                            windSpeed = "10",
                            windFrom = "270",
                            surface = Firmness.FIRM,
                            targetBearing = "90",
                        ),
                    ),
                )
                assertThat(awaitItem()).isEqualTo(BagEffect.ConditionsSaved)
            }
            val stored = conditions.conditions.value
            assertThat(stored.altitudeMeters).isCloseTo(1609.3, 0.1)
            assertThat(stored.temperatureC).isCloseTo(15.0, 1e-6)
            assertThat(stored.wind.speedMps).isCloseTo(4.4704, 1e-6)
            assertThat(stored.surface).isEqualTo(Firmness.FIRM)
            assertThat(conditions.targetBearing.value).isEqualTo(TargetBearing(90.0))
        }

    @Test
    fun invalidConditionsShowWhyAndStoreNothing() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                awaitUntil { it.loaded }
                vm.onEvent(
                    BagEvent.SaveConditions(
                        ConditionsForm.of(Conditions.ISA, null, settings.units.value).copy(altitude = "abc"),
                    ),
                )
                assertThat(
                    awaitUntil { it.conditions.formError != null }.conditions.formError,
                ).isEqualTo("Enter an altitude.")
            }
            assertThat(conditions.conditions.value).isEqualTo(Conditions.ISA)
        }

    @Test
    fun windWithoutATargetDirectionAsksForOne() =
        runTest {
            conditions.conditions.value = Conditions.ISA.copy(wind = Wind(5.0, 180.0))

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.conditions.windNote).isEqualTo(BagCopy.WIND_NEEDS_TARGET)
                assertThat(state.conditions.isStandard).isFalse()
            }
        }

    @Test
    fun thinAirAdjustsTheCarries() =
        runTest {
            conditions.conditions.value = Conditions.ISA.copy(altitudeMeters = 1609.0)
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { row(it, GolfClub.IRON_7)?.avgCarryYards != null }
                assertThat(state.carryAdjusted).isTrue()
                assertThat(row(state, GolfClub.IRON_7)!!.avgCarryYards!! > 160.0).isTrue()
                assertThat(state.conditions.summary).isEqualTo("5,279 ft · 59 °F · calm · normal turf")
            }
        }

    @Test
    fun switchingBagsShowsTheOtherBag() =
        runTest {
            val vm = viewModel()
            vm.uiState.testIgnoringRest {
                awaitUntil { it.clubs.isNotEmpty() }
                val other = bags.createBag("Travel", listOf(GolfClub.DRIVER, GolfClub.IRON_7))
                vm.onEvent(BagEvent.SelectBag(other))
                val state = awaitUntil { it.bagName == "Travel" }
                assertThat(state.clubs.map { it.club }).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7)
                assertThat(state.bags.single { it.isActive }.id).isEqualTo(other)
            }
            assertThat(bags.state.value.size).isEqualTo(2)
            assertThat(vm.uiState.value.bagId).isNotNull()
        }

    private fun row(
        state: BagUiState,
        club: GolfClub,
    ) = state.clubs.firstOrNull { it.club == club }
}
