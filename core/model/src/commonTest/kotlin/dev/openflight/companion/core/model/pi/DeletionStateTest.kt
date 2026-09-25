// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** Ports the Expo `useShotDeletionStore.test.ts` (`feat/delete-shot` e525d90) onto [DeletionState]. */
class DeletionStateTest {
    @Test
    fun beginWaitsOnTheServer() {
        assertThat(DeletionState.Idle.begin(T1)).isEqualTo(DeletionState.Pending(T1))
    }

    @Test
    fun succeedReportsTheDeletionTheServerConfirmed() {
        assertThat(DeletionState.Idle.begin(T1).succeed(T1)).isEqualTo(DeletionState.Deleted(T1))
    }

    @Test
    fun failKeepsTheReasonTheServerGave() {
        assertThat(DeletionState.Idle.begin(T1).fail("Shot not found"))
            .isEqualTo(DeletionState.Failed(T1, "Shot not found"))
    }

    @Test
    fun aFailureWithNothingWaitingIsIgnored() {
        // delete_shot_error is broadcast, so another client's refusal reaches this phone too.
        assertThat(DeletionState.Idle.fail("Shot not found")).isEqualTo(DeletionState.Idle)
        assertThat(
            DeletionState.Idle
                .begin(T1)
                .succeed(T1)
                .fail("Shot not found"),
        ).isEqualTo(DeletionState.Deleted(T1))
    }

    @Test
    fun aDifferentShotIsNotReportedAsDeleted() {
        assertThat(DeletionState.Idle.begin(T1).succeed(T2)).isEqualTo(DeletionState.Pending(T1))
    }

    @Test
    fun aConfirmedDeletionOutranksAFailureNoticedJustBeforeIt() {
        val dropped = DeletionState.Idle.begin(T1).fail(DeletionState.CONNECTION_DROPPED)

        assertThat(dropped.succeed(T1)).isEqualTo(DeletionState.Deleted(T1))
    }

    @Test
    fun onceDismissedNothingIsReported() {
        // Dismissing returns to Idle; a late confirmation then changes nothing.
        assertThat(DeletionState.Idle.succeed(T1)).isEqualTo(DeletionState.Idle)
    }

    private companion object {
        const val T1 = "2026-09-14T10:00:00.123456"
        const val T2 = "2026-09-14T10:05:00.654321"
    }
}
