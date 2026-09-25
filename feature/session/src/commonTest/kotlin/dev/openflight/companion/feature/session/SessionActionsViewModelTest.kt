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
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.model.ConnectionState
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

/** Plan R8f: a delete or clear on the Pi asks first, then shows pending, done or failed. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActionsViewModelTest {
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository()
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

    // Without a Pi link: the phone's own history.

    @Test
    fun deletingALocalRowAsksFirstThenGoesToTheRepositoryByEventId() =
        runTest {
            shots.setHistory(listOf(shot(2), shot(1, club = "7-iron")))

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))

                val confirming = awaitUntil { it.action is SessionActionState.Confirming }.action
                assertThat(confirming).isEqualTo(
                    SessionActionState.Confirming(
                        action = SessionAction.DeleteShot(shotId(1), shotNumber = 1, clubName = "7-Iron"),
                        title = "Delete shot #1?",
                        message = "This 7-Iron shot is removed from this phone.",
                        confirmLabel = "Delete",
                    ),
                )
                // Nothing is deleted before the confirmation.
                assertThat(shots.deleteShotCalls).isEmpty()

                viewModel.onEvent(SessionEvent.ConfirmAction)

                // No Pi link: the phone's history changes at once.
                val done = awaitUntil { it.action is SessionActionState.Done }
                assertThat((done.action as SessionActionState.Done).message).isEqualTo("Shot #1 deleted.")
                assertThat(shots.deleteShotCalls).containsExactly(shotId(1))
                assertThat(shots.deleteShotByTimestampCalls).isEmpty()

                viewModel.onEvent(SessionEvent.DismissAction)
                awaitUntil { it.action == SessionActionState.Idle }
            }
        }

    @Test
    fun cancellingADeleteLeavesTheShot() =
        runTest {
            shots.setHistory(listOf(shot(1)))

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.hasShots }
                viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))
                awaitUntil { it.action is SessionActionState.Confirming }

                viewModel.onEvent(SessionEvent.CancelAction)

                awaitUntil { it.action == SessionActionState.Idle }
                assertThat(shots.deleteShotCalls).isEmpty()
            }
        }

    @Test
    fun clearWithoutAPiLinkAsksThenClearsThePhonesList() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.ClearHistory)
                val confirming = awaitUntil { it.action is SessionActionState.Confirming }.action
                assertThat((confirming as SessionActionState.Confirming).title).isEqualTo("Clear session?")
                assertThat(shots.clearHistoryCalls).isEqualTo(0)

                viewModel.onEvent(SessionEvent.ConfirmAction)

                val done = awaitUntil { it.action is SessionActionState.Done }.action
                assertThat((done as SessionActionState.Done).message).isEqualTo("This phone's list is cleared.")
                assertThat(shots.clearHistoryCalls).isEqualTo(1)
            }
        }

    // With the Pi.

    private fun connectAnn() {
        piSession.linkState.value = PiLinkState.Connected
        piSession.profiles.value =
            ProfilesState(
                profiles = listOf(Profile(id = "ann", name = "Ann"), Profile(id = "bo", name = "Bo")),
                activeProfileId = "ann",
                loaded = true,
            )
        piSession.setSession(listOf(detail(2, profileId = "ann"), detail(1, profileId = "ann")))
    }

    @Test
    fun aPiDeleteIsPendingUntilThePiConfirmsIt() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.source == SessionSource.PI && it.allCount == 2 }
                viewModel.onEvent(SessionEvent.DeleteShot(timestamp(1)))
                val confirming = awaitUntil { it.action is SessionActionState.Confirming }.action
                assertThat((confirming as SessionActionState.Confirming).message)
                    .isEqualTo("This Driver shot is removed from the Pi's session and from this phone.")

                viewModel.onEvent(SessionEvent.ConfirmAction)

                val pending = awaitUntil { it.action is SessionActionState.Pending }
                assertThat((pending.action as SessionActionState.Pending).message).isEqualTo("Deleting shot #1…")
                assertThat(pending.canEdit).isFalse()
                assertThat(shots.deleteShotByTimestampCalls).containsExactly(timestamp(1))
                assertThat(shots.deleteShotCalls).isEmpty()

                // A second request while one is pending is ignored: no double submit.
                viewModel.onEvent(SessionEvent.DeleteShot(timestamp(2)))
                viewModel.onEvent(SessionEvent.ClearHistory)
                assertThat(viewModel.uiState.value.action).isEqualTo(pending.action)

                // The repository's server-confirmed outcome settles it.
                piSession.deletionState.value = DeletionState.Pending(timestamp(1))
                piSession.deletionState.value = DeletionState.Deleted(timestamp(1))

                val done = awaitUntil { it.action is SessionActionState.Done }
                assertThat((done.action as SessionActionState.Done).message).isEqualTo("Shot #1 deleted.")
                assertThat(done.canEdit).isTrue()
            }
        }

    @Test
    fun aRefusedPiDeleteFailsWithTheServersReasonAndCanBeRetried() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.DeleteShot(timestamp(2)))
                viewModel.onEvent(SessionEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                piSession.deletionState.value = DeletionState.Failed(timestamp(2), "Shot not found")

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.title).isEqualTo("Couldn't delete shot #2")
                assertThat(failed.message).isEqualTo("Shot not found")
                assertThat(failed.canRetry).isTrue()

                viewModel.onEvent(SessionEvent.RetryAction)

                awaitUntil { it.action is SessionActionState.Pending }
                assertThat(shots.deleteShotByTimestampCalls).containsExactly(timestamp(2), timestamp(2))
            }
        }

    @Test
    fun aDropWhileAPiDeleteIsPendingFailsItAndRetryWaitsForTheLink() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.DeleteShot(timestamp(1)))
                viewModel.onEvent(SessionEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                // The repository fails a pending delete on a drop (or when the app goes to the background).
                piSession.deletionState.value = DeletionState.Failed(timestamp(1), DeletionState.CONNECTION_DROPPED)
                piSession.linkState.value = PiLinkState.Reconnecting(1, 1_000, "closed")

                val failed =
                    awaitUntil {
                        (it.action as? SessionActionState.Failed)?.canRetry == false
                    }.action as SessionActionState.Failed
                assertThat(failed.message)
                    .isEqualTo("${DeletionState.CONNECTION_DROPPED} ${SessionActionCopy.RECONNECT_TO_RETRY}")

                viewModel.onEvent(SessionEvent.RetryAction)
                assertThat(shots.deleteShotByTimestampCalls).containsExactly(timestamp(1))

                piSession.linkState.value = PiLinkState.Connected
                assertThat((awaitUntil { it.source == SessionSource.PI }.action as SessionActionState.Failed).canRetry)
                    .isTrue()

                viewModel.onEvent(SessionEvent.DismissAction)
                awaitUntil { it.action == SessionActionState.Idle }
                assertThat(piSession.deletionState.value).isEqualTo(DeletionState.Idle)
            }
        }

    @Test
    fun aPiDeleteThatIsNeverAnsweredFailsAfterTheTimeout() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.DeleteShot(timestamp(1)))
                viewModel.onEvent(SessionEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }
                piSession.deletionState.value = DeletionState.Pending(timestamp(1))

                testScheduler.advanceTimeBy(SessionViewModel.ACTION_TIMEOUT_MILLIS + 1)

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.message).isEqualTo(SessionActionCopy.DELETE_NOT_CONFIRMED)
                // The one-at-a-time slot is free again.
                assertThat(piSession.deletionState.value).isEqualTo(DeletionState.Idle)
            }
        }

    @Test
    fun aPiClearNamesTheProfileAndIsDoneWhenThePiConfirmsIt() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.ClearHistory)

                val confirming =
                    awaitUntil { it.action is SessionActionState.Confirming }.action as SessionActionState.Confirming
                assertThat(confirming.title).isEqualTo("Clear Ann's session?")
                assertThat(confirming.message).isEqualTo(
                    "Ann's shots are removed from the Pi's session and from this phone. Other profiles keep theirs.",
                )

                viewModel.onEvent(SessionEvent.ConfirmAction)
                val pending = awaitUntil { it.action is SessionActionState.Pending }.action
                assertThat((pending as SessionActionState.Pending).message).isEqualTo("Clearing Ann's session…")
                assertThat(shots.clearHistoryCalls).isEqualTo(1)

                piSession.clearState.value = ClearState.Pending("ann")
                piSession.clearState.value = ClearState.Cleared("ann")

                val done = awaitUntil { it.action is SessionActionState.Done }.action
                assertThat((done as SessionActionState.Done).message).isEqualTo("Ann's session is cleared.")
            }
        }

    @Test
    fun anUnconfirmedPiClearFailsAndCanOnlyBeRetriedForTheSameProfile() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.ClearHistory)
                viewModel.onEvent(SessionEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                // The repository's 10 s timeout (plan R8c).
                piSession.clearState.value = ClearState.Failed("ann", ClearState.NO_CONFIRMATION)

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.title).isEqualTo("Clear not confirmed")
                assertThat(failed.message).isEqualTo(ClearState.NO_CONFIRMATION)
                assertThat(failed.canRetry).isTrue()

                piSession.profiles.value = piSession.profiles.value.copy(activeProfileId = "bo")

                val blocked =
                    awaitUntil { (it.action as? SessionActionState.Failed)?.canRetry == false }.action
                        as SessionActionState.Failed
                assertThat(blocked.message)
                    .isEqualTo("${ClearState.NO_CONFIRMATION} ${SessionActionCopy.PROFILE_CHANGED}")
                viewModel.onEvent(SessionEvent.RetryAction)
                assertThat(shots.clearHistoryCalls).isEqualTo(1)
            }
        }

    @Test
    fun aPiClearThatNeverLeftFailsAfterTheSafetyTimeout() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.ClearHistory)
                viewModel.onEvent(SessionEvent.ConfirmAction)
                awaitUntil { it.action is SessionActionState.Pending }

                testScheduler.advanceTimeBy(ClearState.TIMEOUT_MILLIS + 1_001)

                val failed = awaitUntil { it.action is SessionActionState.Failed }.action as SessionActionState.Failed
                assertThat(failed.message).isEqualTo(ClearState.NO_CONFIRMATION)
            }
        }

    @Test
    fun aClearConfirmationClosesWhenTheLinkOrTheProfileChanges() =
        runTest {
            connectAnn()

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.allCount == 2 }
                viewModel.onEvent(SessionEvent.ClearHistory)
                awaitUntil { it.action is SessionActionState.Confirming }

                piSession.profiles.value = piSession.profiles.value.copy(activeProfileId = "bo")

                awaitUntil { it.action == SessionActionState.Idle }
                viewModel.onEvent(SessionEvent.ConfirmAction)
                assertThat(shots.clearHistoryCalls).isEqualTo(0)
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
