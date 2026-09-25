// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [DefaultFinalShotStream] over a history flow fed through the real [ShotHistory.record] (plan F3,
 * A2), so a v2 final replaces its provisional version in place exactly as in [ShotRepository].
 */
class FinalShotStreamTest {
    private class Harness {
        private var shots = ShotHistory()
        val history = MutableStateFlow<List<ShotEvent>>(emptyList())
        val stream = DefaultFinalShotStream(history)

        fun arrive(shot: ShotEvent) {
            shots = shots.record(shot)
            history.value = shots.shots
        }
    }

    @Test
    fun aV1ShotIsFinalAsItArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.stream.finalShots().test {
                h.arrive(shot(1))
                assertThat(awaitItem().eventId).isEqualTo(shotId(1))
                h.arrive(shot(2))
                assertThat(awaitItem().eventId).isEqualTo(shotId(2))
                expectNoEvents()
            }
        }

    @Test
    fun aV2ProvisionalShotIsEmittedOnceItsFinalVersionArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.stream.finalShots().test {
                h.arrive(v2(1, final = false))
                expectNoEvents()

                h.arrive(v2(1, final = true, spinRpm = 2_650.0))
                val final = awaitItem()
                assertThat(final.eventId).isEqualTo(shotId(1))
                assertThat(final.final).isEqualTo(true)
                assertThat(final.spinRpm).isEqualTo(2_650.0)
                expectNoEvents()
            }
        }

    @Test
    fun aDuplicateOrALaterCopyOfAFinalShotIsNotEmittedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.stream.finalShots().test {
                h.arrive(shot(1))
                assertThat(awaitItem().eventId).isEqualTo(shotId(1))

                // The same v1 event again, then another shot's arrival re-emitting the list.
                h.arrive(shot(1))
                h.arrive(v2(2, final = true))
                assertThat(awaitItem().eventId).isEqualTo(shotId(2))
                // A v2 update of a final shot (a different final payload) is still the same shot.
                h.arrive(v2(2, final = true, spinRpm = 3_000.0))
                expectNoEvents()
            }
        }

    @Test
    fun shotsInTheHistoryBeforeCollectingAreNotReplayedButAPendingOneIsEmittedWhenFinal() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.arrive(shot(1))
            h.arrive(v2(2, final = false))

            h.stream.finalShots().test {
                expectNoEvents()
                h.arrive(v2(2, final = true))
                assertThat(awaitItem().eventId).isEqualTo(shotId(2))
                expectNoEvents()
            }
        }

    @Test
    fun severalShotsInOneSnapshotAreEmittedOldestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.stream.finalShots().test {
                h.history.value = listOf(shot(3), shot(2))
                assertThat(listOf(awaitItem(), awaitItem()).map { it.eventId }).containsExactly(shotId(2), shotId(3))
            }
        }

    @Test
    fun firstSightingsEmitEachShotOnceAtItsFirstAppearance() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness()
            h.arrive(shot(9))
            h.stream.firstSightings().test {
                h.arrive(v2(1, final = false))
                val sighting = awaitItem()
                assertThat(sighting.eventId).isEqualTo(shotId(1))
                assertThat(sighting.final).isEqualTo(false)

                h.arrive(v2(1, final = true))
                h.arrive(shot(1))
                expectNoEvents()

                h.arrive(shot(2))
                assertThat(awaitItem().eventId).isEqualTo(shotId(2))
            }
        }

    private companion object {
        fun v2(
            number: Int,
            final: Boolean,
            spinRpm: Double? = null,
        ) = shot(number).copy(schemaVersion = 2, final = final, spinRpm = spinRpm)
    }
}
