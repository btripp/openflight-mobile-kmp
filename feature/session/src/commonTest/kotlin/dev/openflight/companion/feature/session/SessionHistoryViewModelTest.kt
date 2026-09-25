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
                        date = "Fri 25 Sep",
                        timeRange = "10:03 – 10:45",
                        shotCount = 2,
                        transportLabel = "Wi-Fi",
                        isCurrent = true,
                        host = "pi.local:8080",
                        spokenDate = "Friday 25 September 2026",
                    ),
                    SessionHistoryRow(
                        id = "older",
                        date = "Sun 13 Sep",
                        timeRange = "09:00",
                        shotCount = 1,
                        transportLabel = "Bluetooth",
                        isCurrent = false,
                        host = "pi.local:8080",
                        spokenDate = "Sunday 13 September 2026",
                    ),
                )
                assertThat(state.sessions[0].shotCountLabel).isEqualTo("2 shots")
                assertThat(state.sessions[1].shotCountLabel).isEqualTo("1 shot")
                assertThat(state.sessions[0].detailLine).isEqualTo("10:03 – 10:45 · Wi-Fi · pi.local:8080")
                assertThat(state.sessions[0].accessibilityLabel)
                    .isEqualTo(
                        "Friday 25 September 2026, 10:03 to 10:45, 2 shots, Wi-Fi, pi.local:8080, current session",
                    )
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
    fun aSessionFromAnotherYearShowsItsYear() =
        runTest {
            history.put("old", listOf(stored(1, "2025-12-31T23:59:00")), host = null)

            SessionHistoryViewModel(history, now = { "2026-09-25T12:00:00Z" }).uiState.testIgnoringRest {
                val row = awaitUntil { it.sessions.isNotEmpty() }.sessions.single()
                assertThat(row.date).isEqualTo("Wed 31 Dec 2025")
                assertThat(row.detailLine).isEqualTo("23:59 · Wi-Fi")
            }
        }

    @Test
    fun clearAllAsksFirstThenIsPendingUntilTheSessionsAreGone() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            history.applyWrites = false
            val viewModel = SessionHistoryViewModel(history)

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.sessions.size == 1 }
                viewModel.onEvent(SessionHistoryEvent.ClearAll)
                assertThat(awaitUntil { it.action is SessionActionState.Confirming }.action)
                    .isEqualTo(SessionActionCopy.confirmClearAll)
                assertThat(history.clearAllCount).isEqualTo(0)

                viewModel.onEvent(SessionHistoryEvent.ConfirmAction)

                val pending = awaitUntil { it.action is SessionActionState.Pending }
                assertThat((pending.action as SessionActionState.Pending).message).isEqualTo("Clearing history…")
                assertThat(history.clearAllCount).isEqualTo(1)
                // No double submit while it's pending.
                viewModel.onEvent(SessionHistoryEvent.ClearAll)
                viewModel.onEvent(SessionHistoryEvent.ConfirmAction)
                assertThat(history.clearAllCount).isEqualTo(1)

                history.applyPendingWrites()

                val done = awaitUntil { it.action is SessionActionState.Done }
                assertThat((done.action as SessionActionState.Done).message).isEqualTo("History cleared.")
                assertThat(done.sessions).isEmpty()
                viewModel.onEvent(SessionHistoryEvent.DismissAction)
                awaitUntil { it.action == SessionActionState.Idle }
            }
        }

    @Test
    fun cancellingClearAllKeepsTheHistory() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            val viewModel = SessionHistoryViewModel(history)

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.sessions.size == 1 }
                viewModel.onEvent(SessionHistoryEvent.ClearAll)
                awaitUntil { it.action is SessionActionState.Confirming }
                viewModel.onEvent(SessionHistoryEvent.CancelAction)
                awaitUntil { it.action == SessionActionState.Idle }
            }
            assertThat(history.clearAllCount).isEqualTo(0)
        }

    @Test
    fun aClearAllTheStoreNeverReflectsFailsAndCanBeRetried() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            history.applyWrites = false
            val viewModel = SessionHistoryViewModel(history)

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.sessions.size == 1 }
                viewModel.onEvent(SessionHistoryEvent.ClearAll)
                viewModel.onEvent(SessionHistoryEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                testScheduler.advanceTimeBy(SessionHistoryViewModel.HISTORY_TIMEOUT_MILLIS + 1)

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.title).isEqualTo("Couldn't clear history")
                assertThat(failed.message).isEqualTo(SessionActionCopy.STORAGE_DID_NOT_RESPOND)
                assertThat(failed.canRetry).isTrue()

                history.applyWrites = true
                viewModel.onEvent(SessionHistoryEvent.RetryAction)

                awaitUntil { it.action is SessionActionState.Done }
            }
            assertThat(history.clearAllCount).isEqualTo(2)
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
                val state = awaitUntil { it.loaded && it.sourceLine != null }
                assertThat(state.title).isEqualTo("Fri 25 Sep")
                assertThat(state.subtitle).isEqualTo("10:00 – 10:10 · 3 shots")
                assertThat(state.sourceLine).isEqualTo("Wi-Fi · pi.local:8080")
                assertThat(state.isCurrent).isFalse()
                // One profile: nothing to filter.
                assertThat(state.profileChips).isEmpty()
                assertThat(state.session.allCount).isEqualTo(3)
                // Same order as the live Session screen, which also reads the rows newest first.
                assertThat(state.session.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 2))
                assertThat(state.session.stats.avgBallSpeedMph).isEqualTo(400.0 / 3)
                assertThat(state.session.shots.map { it.id })
                    .containsExactly("2026-09-25T10:10:00", "2026-09-25T10:05:00", "2026-09-25T10:00:00")
                assertThat(state.session.shots.map { it.shotNumber }).containsExactly(3, 2, 1)
            }
        }

    /** Plan R8f: like the live Session screen, the rows follow the tab (R8h kept them all). */
    @Test
    fun aClubTabFiltersTheStatsAndTheRows() =
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
                assertThat(state.session.shots.map { it.shotNumber }).containsExactly(2)
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
    fun deletingARowAsksFirstThenIsPendingUntilTheShotIsGone() =
        runTest {
            history.put(
                "s1",
                listOf(stored(2, "2026-09-25T10:05:00"), stored(1, "2026-09-25T10:00:00", club = "7-iron")),
            )
            history.applyWrites = false
            val viewModel = detailViewModel()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.session.allCount == 2 }
                viewModel.onEvent(SessionHistoryDetailEvent.DeleteShot("2026-09-25T10:00:00"))

                val confirming = awaitUntil { it.action is SessionActionState.Confirming }.action
                assertThat((confirming as SessionActionState.Confirming).title).isEqualTo("Delete shot #1?")
                assertThat(confirming.message)
                    .isEqualTo("This 7-Iron shot is removed from this phone's history. The Pi isn't changed.")
                assertThat(history.deletedTimestamps).isEmpty()

                viewModel.onEvent(SessionHistoryDetailEvent.ConfirmAction)
                val pending = awaitUntil { it.action is SessionActionState.Pending }
                assertThat(pending.canDelete).isFalse()
                assertThat(history.deletedTimestamps).containsExactly("2026-09-25T10:00:00")

                history.applyPendingWrites()

                val done = awaitUntil { it.action is SessionActionState.Done }
                assertThat(done.session.allCount).isEqualTo(1)
                assertThat((done.action as SessionActionState.Done).message).isEqualTo("Shot #1 deleted.")
            }
        }

    @Test
    fun aDeleteTheStoreNeverReflectsFails() =
        runTest {
            history.put("s1", listOf(stored(1, "2026-09-25T10:00:00")))
            history.applyWrites = false
            val viewModel = detailViewModel()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.session.allCount == 1 }
                viewModel.onEvent(SessionHistoryDetailEvent.DeleteShot("2026-09-25T10:00:00"))
                viewModel.onEvent(SessionHistoryDetailEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                testScheduler.advanceTimeBy(SessionHistoryViewModel.HISTORY_TIMEOUT_MILLIS + 1)

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.title).isEqualTo("Couldn't delete shot #1")
                assertThat(failed.canRetry).isTrue()
            }
        }

    @Test
    fun withTwoProfilesTheDetailFiltersByProfileAndKeepsEachShotsNumber() =
        runTest {
            history.put(
                "s1",
                listOf(
                    stored(3, "2026-09-25T10:10:00", club = "7-iron", ballSpeedMph = 110.0, profile = "bo" to "Bo"),
                    stored(2, "2026-09-25T10:05:00", ballSpeedMph = 150.0, profile = "ann" to "Ann"),
                    stored(1, "2026-09-25T10:00:00", ballSpeedMph = 140.0, profile = "ann" to "Ann"),
                ),
            )
            history.currentSessionId.value = "s1"
            val viewModel = detailViewModel()

            viewModel.uiState.testIgnoringRest {
                val all = awaitUntil { it.profileChips.isNotEmpty() && it.isCurrent }
                // In first-shot order.
                assertThat(all.profileChips)
                    .containsExactly(HistoryProfileChip("ann", "Ann", 2), HistoryProfileChip("bo", "Bo", 1))
                assertThat(all.selectedProfileId).isNull()
                assertThat(all.session.allCount).isEqualTo(3)

                viewModel.onEvent(SessionHistoryDetailEvent.SelectClub("7-iron"))
                awaitUntil { it.session.selectedClub == "7-iron" }
                viewModel.onEvent(SessionHistoryDetailEvent.SelectProfile("ann"))

                val ann = awaitUntil { it.selectedProfileId == "ann" }
                // A profile change starts from every club.
                assertThat(ann.session.selectedClub).isNull()
                assertThat(ann.session.allCount).isEqualTo(2)
                assertThat(ann.session.clubChips).containsExactly(ClubChip("driver", 2))
                assertThat(ann.session.stats.avgBallSpeedMph).isEqualTo(145.0)
                assertThat(ann.session.shots.map { it.shotNumber }).containsExactly(2, 1)

                viewModel.onEvent(SessionHistoryDetailEvent.SelectProfile(null))
                assertThat(awaitUntil { it.selectedProfileId == null }.session.allCount).isEqualTo(3)
            }
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

    // region dates (plan R8f)

    @Test
    fun daysAreWrittenOutWithTheirWeekday() {
        assertThat(historyDay("2026-09-25T10:03:35.906612")).isEqualTo("Fri 25 Sep")
        assertThat(historyDay("2024-02-29T08:00:00")).isEqualTo("Thu 29 Feb")
        assertThat(historyDay("2000-01-01T00:00:00")).isEqualTo("Sat 1 Jan")
        assertThat(historyDay("2026-03-01T00:00:00", currentYear = 2026)).isEqualTo("Sun 1 Mar")
        assertThat(historyDay("2025-12-31T23:59:00", currentYear = 2026)).isEqualTo("Wed 31 Dec 2025")
        assertThat(historySpokenDay("2026-09-25T10:03:35")).isEqualTo("Friday 25 September 2026")
    }

    @Test
    fun somethingThatIsNotADateIsShownAsItIs() {
        assertThat(historyDay("yesterday")).isEqualTo("yesterday")
        assertThat(historyDay("2026-13-01T00:00:00")).isEqualTo("2026-13-01")
    }

    // endregion

    private fun detailViewModel() = SessionHistoryDetailViewModel("s1", history, settings)

    private fun stored(
        number: Int,
        timestamp: String,
        club: String = "driver",
        ballSpeedMph: Double = 140.0,
        eventId: String? = null,
        profile: Pair<String, String>? = null,
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
                profileId = profile?.first,
                profileName = profile?.second,
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
