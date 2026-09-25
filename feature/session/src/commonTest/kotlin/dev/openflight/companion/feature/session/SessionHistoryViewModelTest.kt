// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
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

/** The session history list and a stored session's detail (plan R8h). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryViewModelTest {
    private val history = FakeShotHistoryRepository()
    private val settings = FakeSettingsRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region list

    @Test
    fun beforeTheFirstReadNothingIsLoaded() {
        assertThat(SessionHistoryViewModel(history).uiState.value.loaded).isFalse()
    }

    @Test
    fun withNoStoredSessionsTheListIsEmptyButLoaded() =
        runTest {
            SessionHistoryViewModel(history).uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.sessions).isEmpty()
                assertThat(state.isPersistent).isTrue()
            }
        }

    @Test
    fun sessionsAreListedNewestFirstWithDateShotCountAndFirstAndLastTime() =
        runTest {
            history.put("older", listOf(stored(1, "2026-09-13T09:00:10")), transport = TransportType.BLUETOOTH)
            history.put(
                "newer",
                listOf(stored(2, "2026-09-25T10:45:00.5"), stored(1, "2026-09-25T10:03:35.906612")),
            )
            history.currentSessionId.value = "newer"

            SessionHistoryViewModel(history).uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.sessions).containsExactly(
                    SessionHistoryRow(
                        id = "newer",
                        date = "2026-09-25",
                        timeRange = "10:03 – 10:45",
                        shotCount = 2,
                        transportLabel = "Wi-Fi",
                        isCurrent = true,
                    ),
                    SessionHistoryRow(
                        id = "older",
                        date = "2026-09-13",
                        timeRange = "09:00",
                        shotCount = 1,
                        transportLabel = "Bluetooth",
                        isCurrent = false,
                    ),
                )
                assertThat(state.sessions[0].shotCountLabel).isEqualTo("2 shots")
                assertThat(state.sessions[1].shotCountLabel).isEqualTo("1 shot")
            }
        }

    @Test
    fun anUnavailableDatabaseIsShown() =
        runTest {
            history.isPersistent.value = false

            SessionHistoryViewModel(history).uiState.testIgnoringRest {
                assertThat(awaitUntil { it.loaded }.isPersistent).isFalse()
            }
        }

    @Test
    fun clearAllEmptiesTheHistory() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            val viewModel = SessionHistoryViewModel(history)

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.sessions.size == 1 }
                viewModel.onEvent(SessionHistoryEvent.ClearAll)
                awaitUntil { it.sessions.isEmpty() }
            }
            assertThat(history.clearAllCount).isEqualTo(1)
        }

    // endregion

    // region detail

    @Test
    fun theDetailShowsTheSessionsTabsStatsAndRowsNewestFirst() =
        runTest {
            history.put(
                "s1",
                listOf(
                    stored(3, "2026-09-25T10:10:00", club = "7-iron", ballSpeedMph = 110.0),
                    stored(2, "2026-09-25T10:05:00", ballSpeedMph = 150.0),
                    stored(1, "2026-09-25T10:00:00", ballSpeedMph = 140.0),
                ),
            )

            detailViewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.title).isEqualTo("2026-09-25")
                assertThat(state.subtitle).isEqualTo("10:00 – 10:10 · 3 shots")
                assertThat(state.session.allCount).isEqualTo(3)
                // Same order as the live Session screen, which also reads the rows newest first.
                assertThat(state.session.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 2))
                assertThat(state.session.stats.avgBallSpeedMph).isEqualTo(400.0 / 3)
                assertThat(state.session.shots.map { it.id })
                    .containsExactly("2026-09-25T10:10:00", "2026-09-25T10:05:00", "2026-09-25T10:00:00")
                assertThat(state.session.shots.map { it.shotNumber }).containsExactly(3, 2, 1)
            }
        }

    @Test
    fun aClubTabFiltersTheStatsButNotTheRows() =
        runTest {
            history.put(
                "s1",
                listOf(
                    stored(2, "2026-09-25T10:05:00", club = "7-iron", ballSpeedMph = 110.0),
                    stored(1, "2026-09-25T10:00:00", ballSpeedMph = 140.0),
                ),
            )
            val viewModel = detailViewModel()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.loaded }
                viewModel.onEvent(SessionHistoryDetailEvent.SelectClub("7-iron"))
                val state = awaitUntil { it.session.selectedClub == "7-iron" }
                assertThat(state.session.stats.avgBallSpeedMph).isEqualTo(110.0)
                assertThat(state.session.shots.size).isEqualTo(2)
            }
        }

    @Test
    fun theDetailFollowsTheUnitSetting() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            settings.units.value = UnitSystem.METRIC

            detailViewModel().uiState.testIgnoringRest {
                assertThat(awaitUntil { it.loaded }.session.units).isEqualTo(UnitSystem.METRIC)
            }
        }

    @Test
    fun aSwingSpeedSessionShowsSwingStats() =
        runTest {
            history.put(
                "s1",
                listOf(
                    HistoryShot(2, "s1", null, swingRep(2, speedMph = 90.0)),
                    HistoryShot(1, "s1", null, swingRep(1, speedMph = 80.0)),
                ),
            )

            detailViewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.session.swingStats?.bestSpeedMph).isEqualTo(90.0)
                assertThat(state.session.swingStats?.lastSpeedMph).isEqualTo(90.0)
            }
        }

    @Test
    fun deletingARowRemovesThatShotFromTheStoredHistory() =
        runTest {
            history.put("s1", listOf(stored(2, "2026-09-25T10:05:00"), stored(1, "2026-09-25T10:00:00")))
            val viewModel = detailViewModel()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.session.allCount == 2 }
                viewModel.onEvent(SessionHistoryDetailEvent.DeleteShot("2026-09-25T10:00:00"))
                awaitUntil { it.session.allCount == 1 }
            }
            assertThat(history.deletedTimestamps).containsExactly("2026-09-25T10:00:00")
        }

    @Test
    fun exportBuildsThisSessionsCsvOldestFirst() =
        runTest {
            history.put(
                "s1",
                listOf(
                    stored(2, "2026-09-25T10:05:00", eventId = shotId(2)),
                    stored(1, "2026-09-25T10:00:00"),
                ),
            )
            val viewModel = detailViewModel()

            viewModel.effects.test {
                viewModel.onEvent(SessionHistoryDetailEvent.ExportCsv)
                val csv = awaitItem() as SessionEffect.CsvReady
                val lines = csv.csv.lines()
                assertThat(lines[1].startsWith("1,,2026-09-25T10:00:00,")).isTrue()
                assertThat(lines[2].startsWith("2,${shotId(2)},2026-09-25T10:05:00,")).isTrue()
                assertThat(csv.filename).isEqualTo("openflight-shots-2026-09-25T10-00-00.csv")
            }
        }

    @Test
    fun anEmptySessionExportsNothing() =
        runTest {
            val viewModel = detailViewModel()

            viewModel.effects.test {
                viewModel.onEvent(SessionHistoryDetailEvent.ExportCsv)
                expectNoEvents()
            }
            assertThat(viewModel.uiState.value.session.selectedClub).isNull()
        }

    // endregion

    private fun detailViewModel() = SessionHistoryDetailViewModel("s1", history, settings)

    private fun stored(
        number: Int,
        timestamp: String,
        club: String = "driver",
        ballSpeedMph: Double = 140.0,
        eventId: String? = null,
    ) = HistoryShot(
        id = number.toLong(),
        sessionId = "s1",
        eventId = eventId,
        detail =
            ShotDetail(
                timestamp = timestamp,
                shotNumber = number,
                ballSpeedMph = ballSpeedMph,
                estimatedCarryYards = 250.0,
                club = club,
            ),
    )

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
