// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository()

    /** Starts disconnected, so the screen shows the phone's own history unless a test connects it. */
    private val piSession = FakePiSessionRepository(PiLinkState.Connecting)
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SessionViewModel(shots, settings, piSession, now = { "2026-09-24T18:30:05.123Z" })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region local history (plan R5a)

    @Test
    fun withNoShotsTheStateIsEmpty() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { true }
                assertThat(state.hasShots).isEqualTo(false)
                assertThat(state.allCount).isEqualTo(0)
                assertThat(state.clubChips).isEmpty()
                assertThat(state.selectedClub).isNull()
                assertThat(state.source).isEqualTo(SessionSource.LOCAL)
            }
        }

    @Test
    fun theAllTabAggregatesEveryClub() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0)))
                val state = awaitUntil { it.allCount == 2 }

                assertThat(state.hasShots).isTrue()
                assertThat(state.stats.shotCount).isEqualTo(2)
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
                assertThat(state.shots.map { it.id }).containsExactly(shotId(2), shotId(1))
                assertThat(state.shots.map { it.shotNumber }).containsExactly(2, 1)
            }
        }

    @Test
    fun selectingAClubFiltersTheStatsAndTheListToThatClubOnly() =
        runTest {
            shots.setHistory(listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0)))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                val state = awaitUntil { it.selectedClub == "7-iron" }

                assertThat(state.stats.shotCount).isEqualTo(1)
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(120.0)
                // allCount and the chips stay over the full history; the rows follow the tab.
                assertThat(state.allCount).isEqualTo(2)
                assertThat(state.shots.map { it.id }).containsExactly(shotId(2))
                assertThat(state.shots.single().shotNumber).isEqualTo(2)
            }
        }

    @Test
    fun selectingNullReturnsToTheAllTab() =
        runTest {
            shots.setHistory(listOf(shot(1, club = "7-iron")))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                awaitUntil { it.selectedClub == "7-iron" }

                viewModel.onEvent(SessionEvent.SelectClub(null))
                val state = awaitUntil { it.selectedClub == null }
                assertThat(state.stats.shotCount).isEqualTo(1)
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
    fun localRowsCarryThePiDetailWhenItIsKnown() =
        runTest {
            shots.setHistory(listOf(shot(1)))
            piSession.shotDetails.value = mapOf(timestamp(1) to detail(1, profileName = "Ann"))

            viewModel.uiState.testIgnoringRest {
                val row = awaitUntil { it.shots.isNotEmpty() }.shots.single()

                assertThat(row.profileName).isEqualTo("Ann")
                assertThat(row.enrichment?.launchAngleConfidence).isEqualTo(ConfidenceLevel.MEDIUM)
            }
        }

    @Test
    fun deletingALocalRowGoesToTheRepositoryByEventId() =
        runTest {
            shots.setHistory(listOf(shot(2), shot(1)))

            viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))

            assertThat(shots.deleteShotCalls).containsExactly(shotId(1))
            assertThat(shots.deleteShotByTimestampCalls).isEmpty()
        }

    @Test
    fun clearHistoryEventGoesToTheRepository() =
        runTest {
            viewModel.onEvent(SessionEvent.ClearHistory)

            assertThat(shots.clearHistoryCalls).isEqualTo(1)
        }

    @Test
    fun exportCsvProducesTheFullHistoryOldestFirstRegardlessOfTheSelectedTab() =
        runTest {
            shots.setHistory(listOf(shot(2, club = "7-iron"), shot(1, club = "driver")))
            viewModel.onEvent(SessionEvent.SelectClub("7-iron"))

            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.ExportCsv)
                val effect = awaitItem() as SessionEffect.CsvReady

                val lines = effect.csv.lines()
                // shot 1 (oldest, driver) is row 1; shot 2 (newest, 7-iron) is row 2 -- unaffected by
                // the "7-iron" tab selection, matching the web UI's export.
                assertThat(lines[1].startsWith("1,${shotId(1)}")).isTrue()
                assertThat(lines[2].startsWith("2,${shotId(2)}")).isTrue()
                // No Pi detail known: the R5a columns only.
                assertThat(lines[0].endsWith("spin_axis_deg")).isTrue()
                assertThat(effect.filename).isEqualTo("openflight-shots-2026-09-24T18-30-05-123Z.csv")
            }
        }

    // endregion

    // region Pi session (plan R6b)

    @Test
    fun whileThePiIsConnectedTheSessionComesFromThePiWithItsStatsComputedHere() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(2, club = "7-iron", ballSpeedMph = 120.0), detail(1)))
            // The server's stats cover every profile, so they're not used.
            piSession.stats.value =
                SessionStats(shotCount = 9, avgBallSpeed = 99.0, maxBallSpeed = 99.0, avgCarryEst = 250.0)

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.source == SessionSource.PI && it.allCount == 2 }

                assertThat(state.shots.map { it.id }).containsExactly(timestamp(2), timestamp(1))
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(130.0)
                assertThat(state.stats.minBallSpeedMph).isEqualTo(120.0)
                assertThat(state.stats.stdDevBallSpeedMph).isCloseTo(sqrt(200.0), 1e-9)
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
                assertThat(state.shots.first().enrichment).isNotNull()

                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                val tab = awaitUntil { it.selectedClub == "7-iron" }
                assertThat(tab.stats.shotCount).isEqualTo(1)
                assertThat(tab.stats.avgBallSpeedMph).isEqualTo(120.0)
            }
        }

    @Test
    fun onlyTheActiveProfilesRowsAndStatsAreShown() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(
                listOf(
                    detail(3, ballSpeedMph = 150.0, profileId = "sam"),
                    detail(2, ballSpeedMph = 110.0, profileId = "alex"),
                    detail(1, ballSpeedMph = 100.0, profileId = "alex"),
                ),
            )
            piSession.profiles.value =
                ProfilesState(
                    profiles = listOf(Profile(id = "alex", name = "Alex"), Profile(id = "sam", name = "Sam")),
                    activeProfileId = "alex",
                    loaded = true,
                )

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.source == SessionSource.PI && it.allCount == 2 }

                assertThat(state.shots.map { it.id }).containsExactly(timestamp(2), timestamp(1))
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(105.0)

                // Another client (or this phone) switches the active profile.
                piSession.profiles.value = piSession.profiles.value.copy(activeProfileId = "sam")
                val sam = awaitUntil { it.allCount == 1 }
                assertThat(sam.shots.single().id).isEqualTo(timestamp(3))
            }
        }

    @Test
    fun whenTheLinkDropsTheScreenFallsBackToTheLocalHistory() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(2), detail(1)))

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.source == SessionSource.PI }

                piSession.linkState.value = PiLinkState.Reconnecting(1, 1_000, "closed")

                val state = awaitUntil { it.source == SessionSource.LOCAL }
                assertThat(state.shots.map { it.id }).containsExactly(shotId(9))
            }
        }

    @Test
    fun aSwingSpeedTabShowsSwingStats() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(swingRep(3, 95.0), swingRep(2, 100.0), swingRep(1, 90.0)))

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.swingStats != null }

                assertThat(state.swingStats?.count).isEqualTo(3)
                assertThat(state.swingStats?.lastSpeedMph).isEqualTo(95.0)
                assertThat(state.swingStats?.bestSpeedMph).isEqualTo(100.0)
                assertThat(state.shots.first().isSwingSpeed).isTrue()
                assertThat(state.shots.first().implementLabel).isEqualTo("Stack 100g")
            }
        }

    @Test
    fun deletingAPiRowGoesToTheRepositoryByTimestamp() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(1)))

            viewModel.onEvent(SessionEvent.DeleteShot(timestamp(1)))

            assertThat(shots.deleteShotByTimestampCalls).containsExactly(timestamp(1))
            assertThat(shots.deleteShotCalls).isEmpty()
        }

    @Test
    fun aFailedPiDeleteBecomesAMessageOnce() =
        runTest {
            viewModel.effects.test {
                piSession.deletionState.value = DeletionState.Failed(timestamp(1), "Shot not found")

                assertThat(awaitItem()).isEqualTo(SessionEffect.Message("Shot not found"))
                assertThat(piSession.deletionState.value).isEqualTo(DeletionState.Idle)
            }
        }

    @Test
    fun anUnconfirmedPiClearBecomesAMessageOnce() =
        runTest {
            viewModel.effects.test {
                piSession.clearState.value = ClearState.Failed("p1", ClearState.NO_CONFIRMATION)

                assertThat(awaitItem()).isEqualTo(SessionEffect.Message(ClearState.NO_CONFIRMATION))
                assertThat(piSession.clearState.value).isEqualTo(ClearState.Idle)
            }
        }

    @Test
    fun exportWhileConnectedUsesThePiSessionWithTheWebExportColumns() =
        runTest {
            shots.setHistory(listOf(shot(1)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(swingRep(2, 97.4), detail(1)))

            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.ExportCsv)
                val lines = (awaitItem() as SessionEffect.CsvReady).csv.lines()

                assertThat(
                    lines[0].endsWith(
                        "profile,mode,implement,openflight_speed_mph,reading_count," +
                            "trigger_speed_mph,duration_ms,peak_magnitude",
                    ),
                ).isTrue()
                // Row 1 is the oldest, and keeps the phone's event id for the shot it also received.
                assertThat(lines[1].startsWith("1,${shotId(1)},${timestamp(1)},driver,")).isTrue()
                assertThat(lines[2]).isEqualTo(
                    "2,,${timestamp(
                        2,
                    )},Swing Speed,97.4,97.4,,,,,,,,Ann,swing-speed,Stack 100g,97.4,5,80.1,1200.0,210.5",
                )
            }
        }

    @Test
    fun simulateShotIsHiddenOnARealPiEvenWhileConnected() =
        runTest {
            // Plan R8d: only an explicit mock_mode == true offers it (Expo showed it always).
            piSession.linkState.value = PiLinkState.Connected
            viewModel.uiState.testIgnoringRest {
                piSession.mockMode.value = true
                awaitUntil { it.showSimulateShot }
                piSession.mockMode.value = false
                awaitUntil { !it.showSimulateShot }
            }
        }

    @Test
    fun simulateShotIsVisibleOnlyInMockModeAndSendsTheCommand() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected

            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { true }.showSimulateShot).isFalse()

                piSession.mockMode.value = true
                piSession.triggerStatus.value = TriggerStatus(mode = "swing-speed")
                val state = awaitUntil { it.showSimulateShot && it.simulateLabel == SessionUiState.SIMULATE_SWING }
                assertThat(state.simulateAvailability).isEqualTo(PiFeatureAvailability.Available)
            }

            viewModel.onEvent(SessionEvent.SimulateShot)

            assertThat(piSession.commands).containsExactly("simulate_shot")
        }

    @Test
    fun simulateShotWithoutALinkReportsWhy() =
        runTest {
            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.SimulateShot)

                assertThat(awaitItem()).isEqualTo(SessionEffect.Message("Not connected to the Pi's live session yet."))
            }
            assertThat(viewModel.uiState.value.simulateAvailability)
                .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED))
        }

    // endregion

    // region Bluetooth is read-and-select (plan R8e)

    @Test
    fun overBluetoothDeleteAndClearAreWifiOnlyAndExplainWhy() =
        runTest {
            settings.transport.value = TransportType.BLUETOOTH
            piSession.linkState.value = PiLinkState.WifiOnly
            shots.setHistory(listOf(shot(1)))

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.hasShots }
                assertThat(state.editAvailability)
                    .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH))
                viewModel.effects.test {
                    viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))
                    viewModel.onEvent(SessionEvent.ClearHistory)

                    val message = SessionEffect.Message(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH)
                    assertThat(awaitItem()).isEqualTo(message)
                    assertThat(awaitItem()).isEqualTo(message)
                }
            }
            assertThat(shots.deleteShotCalls).isEmpty()
            assertThat(shots.clearHistoryCalls).isEqualTo(0)
        }

    @Test
    fun onWifiDeleteAndClearStayAvailable() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { true }.editAvailability).isEqualTo(PiFeatureAvailability.Available)
            }
        }

    // endregion

    // region dispersion chart

    @Test
    fun aHorizontalLaunchToTheRightLandsRightAndToTheLeftLandsLeft() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2, launchAngleHorizontal = -4.0), shot(1, launchAngleHorizontal = 4.0)))

                val points = awaitUntil { it.dispersion?.points?.size == 2 }.dispersion!!.points
                assertThat(points.map { it.id }).containsExactly(shotId(2), shotId(1))
                assertThat(points[0].offlineYards).isLessThan(0.0)
                assertThat(points[1].offlineYards).isGreaterThan(0.0)
                assertThat(points.map { it.sideEstimated }).containsExactly(false, false)
                assertThat(points.map { it.shotNumber }).containsExactly(2, 1)
            }
        }

    @Test
    fun aShotWithNoSideDataSitsOnTheTargetLineAndIsFlagged() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(1)))

                val dispersion = awaitUntil { it.dispersion != null }.dispersion!!
                assertThat(dispersion.points.single().offlineYards).isCloseTo(0.0, 0.001)
                assertThat(dispersion.points.single().sideEstimated).isTrue()
                assertThat(dispersion.estimatedSideCount).isEqualTo(1)
            }
        }

    @Test
    fun swingRepsAreLeftOffThePisChart() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                piSession.linkState.value = PiLinkState.Connected
                piSession.setSession(listOf(swingRep(2, speedMph = 95.0), detail(1, club = "7-iron")))

                val state = awaitUntil { it.source == SessionSource.PI && it.dispersion != null }
                val point = state.dispersion!!.points.single()
                assertThat(point.id).isEqualTo(timestamp(1))
                assertThat(point.shortLabel).isEqualTo("7i")
            }
        }

    @Test
    fun theClubTabFiltersTheChartButKeepsEachClubsColour() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(
                    listOf(
                        shot(4, club = "7-iron", carryYards = 160.0),
                        shot(3, launchAngleHorizontal = 2.0),
                        shot(2, launchAngleHorizontal = 0.5, carryYards = 240.0),
                        shot(1, launchAngleHorizontal = -2.0, carryYards = 260.0),
                    ),
                )
                val all = awaitUntil { it.dispersion?.points?.size == 4 }.dispersion!!
                assertThat(all.ellipses.map { it.club }).containsExactly("driver")
                assertThat(all.points.first().colorIndex).isEqualTo(1)

                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))

                val ironOnly = awaitUntil { it.dispersion?.points?.size == 1 }.dispersion!!
                assertThat(ironOnly.points.single().colorIndex).isEqualTo(1)
                assertThat(ironOnly.ellipses).isEmpty()
            }
        }

    @Test
    fun selectingAShotFillsTheCardAndDeletingItClearsIt() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2), shot(1, club = "7-iron", carryYards = 162.0)))
                awaitUntil { it.dispersion?.points?.size == 2 }

                viewModel.onEvent(SessionEvent.SelectShot(shotId(1)))

                val card = awaitUntil { it.selectedShot != null }.selectedShot!!
                assertThat(card).isEqualTo(
                    SelectedShotCard(
                        id = shotId(1),
                        shotNumber = 1,
                        clubName = "7-Iron",
                        carryYards = 162.0,
                        spinRpm = null,
                        clubSpeedMph = null,
                    ),
                )

                shots.setHistory(listOf(shot(2)))

                assertThat(awaitUntil { it.shots.size == 1 }.selectedShot).isNull()
            }
        }

    @Test
    fun selectingAShotFromAnotherClubSwitchesBackToAll() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2), shot(1, club = "7-iron")))
                viewModel.onEvent(SessionEvent.SelectClub("driver"))
                awaitUntil { it.selectedClub == "driver" && it.dispersion?.points?.size == 1 }

                viewModel.onEvent(SessionEvent.SelectShot(shotId(1)))

                val state = awaitUntil { it.selectedShot != null }
                assertThat(state.selectedClub).isNull()
                assertThat(state.selectedShot?.id).isEqualTo(shotId(1))
            }
        }

    @Test
    fun switchingTheClubTabClearsTheSelection() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2), shot(1)))
                viewModel.onEvent(SessionEvent.SelectShot(shotId(2)))
                awaitUntil { it.selectedShot != null }

                viewModel.onEvent(SessionEvent.SelectClub("driver"))

                val state = awaitUntil { it.selectedClub == "driver" }
                assertThat(state.selectedShot).isNull()
            }
        }

    @Test
    fun shotsWithoutSideDataDoNotShapeTheEllipse() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                // Two measured drivers and three without side data: too few measured for an ellipse.
                shots.setHistory(
                    listOf(
                        shot(5),
                        shot(4, carryYards = 255.0),
                        shot(3, carryYards = 245.0),
                        shot(2, launchAngleHorizontal = 3.0, carryYards = 260.0),
                        shot(1, launchAngleHorizontal = -3.0, carryYards = 240.0),
                    ),
                )

                val dispersion = awaitUntil { it.dispersion?.points?.size == 5 }.dispersion!!
                assertThat(dispersion.ellipses).isEmpty()
            }
        }

    @Test
    fun aShotFarFromTheRestOfItsClubIsAPossibleBadReadAndLeftOutOfTheSpread() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(
                    listOf(
                        shot(6, club = "7-iron", launchAngleHorizontal = 1.0, carryYards = 238.0),
                        shot(5, club = "7-iron", launchAngleHorizontal = -1.0, carryYards = 160.0),
                        shot(4, club = "7-iron", launchAngleHorizontal = 2.0, carryYards = 164.0),
                        shot(3, club = "7-iron", launchAngleHorizontal = -2.0, carryYards = 158.0),
                        shot(2, club = "7-iron", launchAngleHorizontal = 0.5, carryYards = 166.0),
                        shot(1, club = "7-iron", launchAngleHorizontal = 1.5, carryYards = 162.0),
                    ),
                )
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))

                val dispersion = awaitUntil { it.dispersion?.clubSpread != null }.dispersion!!
                assertThat(dispersion.points.filter { it.possibleBadRead }.map { it.id }).containsExactly(shotId(6))
                assertThat(dispersion.possibleBadReadCount).isEqualTo(1)
                val spread = dispersion.clubSpread!!
                assertThat(spread.clubName).isEqualTo("7-Iron")
                assertThat(spread.shotCount).isEqualTo(5)
                assertThat(spread.excludedCount).isEqualTo(1)
                assertThat(spread.avgCarryYards).isCloseTo(162.0, 0.001)
                assertThat(spread.avgOfflineYards).isNotNull().isGreaterThan(0.0)
                assertThat(spread.widthYards).isNotNull().isGreaterThan(0.0)
                assertThat(spread.depthYards).isNotNull().isGreaterThan(0.0)

                viewModel.onEvent(SessionEvent.SelectShot(shotId(6)))

                assertThat(awaitUntil { it.selectedShot != null }.selectedShot!!.possibleBadRead).isTrue()
            }
        }

    @Test
    fun theAllTabHasNoClubSpread() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(3), shot(2), shot(1)))

                assertThat(awaitUntil { it.dispersion != null }.dispersion!!.clubSpread).isNull()
            }
        }

    @Test
    fun onlyTheActiveProfilesShotsAreOnTheChartWithTheirRowsIdsAndNumbers() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(
                listOf(
                    detail(3, profileId = "sam"),
                    detail(2, club = "7-iron", profileId = "alex"),
                    detail(1, profileId = "alex"),
                ),
            )
            piSession.profiles.value =
                ProfilesState(
                    profiles = listOf(Profile(id = "alex", name = "Alex"), Profile(id = "sam", name = "Sam")),
                    activeProfileId = "alex",
                    loaded = true,
                )

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.source == SessionSource.PI && it.dispersion?.points?.size == 2 }
                val points = state.dispersion!!.points
                assertThat(points.map { it.id }).containsExactly(timestamp(2), timestamp(1))
                assertThat(points.map { it.id }).isEqualTo(state.shots.map { it.id })
                assertThat(points.map { it.shotNumber }).isEqualTo(state.shots.map { it.shotNumber })

                viewModel.onEvent(SessionEvent.SelectShot(timestamp(2)))
                assertThat(awaitUntil { it.selectedShot != null }.selectedShot!!.shotNumber).isEqualTo(2)

                piSession.profiles.value = piSession.profiles.value.copy(activeProfileId = "sam")
                val sam = awaitUntil { it.dispersion?.points?.size == 1 }
                assertThat(
                    sam.dispersion!!
                        .points
                        .single()
                        .id,
                ).isEqualTo(timestamp(3))
                // The selected shot belongs to the other profile now: no card.
                assertThat(sam.selectedShot).isNull()
            }
        }

    @Test
    fun overBluetoothAShotCanStillBeSelectedButNotDeleted() =
        runTest {
            settings.transport.value = TransportType.BLUETOOTH
            piSession.linkState.value = PiLinkState.WifiOnly
            shots.setHistory(listOf(shot(1)))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectShot(shotId(1)))
                assertThat(awaitUntil { it.selectedShot != null }.selectedShot!!.id).isEqualTo(shotId(1))
                viewModel.effects.test {
                    viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))

                    assertThat(awaitItem())
                        .isEqualTo(SessionEffect.Message(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH))
                }
            }
            assertThat(shots.deleteShotCalls).isEmpty()
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
