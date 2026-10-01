// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.Vec3
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.test.Test

/** Plan F8a1: the pure helpers behind replay, the overlay and the roll-out marker. */
class RangeFlightsTest {
    @Test
    fun aStoredShotFliesUnderItsEventIdOrItsRowId() {
        val withEvent = storedShot(id = 7)
        val socketOnly = storedShot(id = 300, eventId = null)
        assertThat(withEvent.toRangeShotEvent()!!.eventId).isEqualTo(eventIdFor(7))
        assertThat(socketOnly.toRangeShotEvent()!!.eventId).isEqualTo("00000000-0000-4000-8000-00000000012c")
        assertThat(withEvent.toRangeShotEvent()!!.estimatedCarryYards).isEqualTo(200.0)
    }

    @Test
    fun aSwingSpeedRepOrAShotWithoutCarryDoesNotFly() {
        val rep = storedShot(id = 1, detail = ShotDetail(timestamp = "t", club = "Swing Speed", ballSpeedMph = 100.0))
        val noCarry = storedShot(id = 2, detail = ShotDetail(timestamp = "t", club = "driver", ballSpeedMph = 150.0))
        assertThat(rep.toRangeShotEvent()).isNull()
        assertThat(noCarry.toRangeShotEvent()).isNull()
        assertThat(noCarry.toRangeShotItem(1).flyable).isFalse()
        assertThat(storedShot(id = 3).toRangeShotItem(1).flyable).isTrue()
    }

    @Test
    fun theShotListShowsTheSpinAdjustedCarryWhenThePiSentOne() {
        val row = ShotDetail(timestamp = "t", club = "9-iron", ballSpeedMph = 112.7, estimatedCarryYards = 166.0)
        assertThat(storedShot(id = 1, detail = row.copy(carrySpinAdjusted = 144.0)).toRangeShotItem(1).carryYards)
            .isEqualTo(144.0)
        assertThat(storedShot(id = 2, detail = row).toRangeShotItem(1).carryYards).isEqualTo(166.0)
        assertThat(storedShot(id = 3, detail = row.copy(carrySpinAdjusted = 0.0)).toRangeShotItem(1).carryYards)
            .isEqualTo(166.0)
    }

    @Test
    fun clubsAreOrderedDriverFirstAndUnknownsLast() {
        assertThat(orderClubs(listOf("pw", "zzz", "driver", "7-iron", "driver")))
            .containsExactly("driver", "7-iron", "pw", "zzz")
    }

    @Test
    fun downsamplingKeepsTheLaunchAndTheLanding() {
        val trajectory = straightTrajectory(pointCount = 301)
        val sampled = trajectory.downsampled(41)
        assertThat(sampled.points.size).isEqualTo(41)
        assertThat(sampled.points.first()).isEqualTo(trajectory.points.first())
        assertThat(sampled.points.last()).isEqualTo(trajectory.points.last())
        assertThat(sampled.carryMeters).isEqualTo(trajectory.carryMeters)
        val short = straightTrajectory(pointCount = 5)
        assertThat(short.downsampled(41)).isSameInstanceAs(short)
    }

    @Test
    fun theRollOutContinuesAlongTheLandingDirection() {
        // Landing at (0, 0, 100) m heading straight downrange: 10 yd of roll adds 9.144 m.
        val straight = rollOutEnd(straightTrajectory(pointCount = 3), rollYards = 10.0)
        assertThat(straight.x).isCloseTo(0.0, 1e-9)
        assertThat(straight.z).isCloseTo(100.0 + 9.144, 1e-9)

        // A 3-4-5 heading to the right.
        val angled =
            FlightTrajectory(
                eventId = "a",
                points = listOf(FlightPoint(1.0, Vec3(5.0, 0.0, 90.0), Vec3(3.0, -5.0, 4.0))),
                provenance = FlightInputProvenance(),
            )
        val end = rollOutEnd(angled, rollYards = 5.0 / 0.9144)
        assertThat(end.x).isCloseTo(8.0, 1e-9)
        assertThat(end.z).isCloseTo(94.0, 1e-9)
    }

    @Test
    fun aTapSelectsTheNearestLandingWithinReach() {
        val projection = RangeProjection(RangeCameraPlanner().pose, 1080f, 1920f)
        val near = overlayFlight("near", Vec3(-5.0, 0.0, 100.0))
        val far = overlayFlight("far", Vec3(10.0, 0.0, 200.0))
        val nearScreen = projection.project(RangeProjection.flightToScene(Vec3(-5.0, 0.0, 100.0)).copy(y = 0.0))

        assertThat(nearestOverlayLanding(projection, listOf(far, near), nearScreen.x + 4, nearScreen.y, 48f))
            .isEqualTo("near")
        assertThat(nearestOverlayLanding(projection, listOf(far, near), 5f, 5f, 48f)).isNull()
    }

    @Test
    fun anUntitledSessionIsNamedByItsFirstShot() {
        val option =
            RangeSessionOption.of(
                HistorySession(
                    id = "s",
                    startedAtEpochMillis = 0,
                    host = null,
                    transport = null,
                    shotCount = 12,
                    firstShotAt = "2026-09-25T10:03:12.123",
                    lastShotAt = "2026-09-25T10:45:00",
                ),
            )
        assertThat(option.title).isEqualTo("2026-09-25 10:03")
        assertThat(option.shotCount).isEqualTo(12)
    }

    private fun overlayFlight(
        id: String,
        landing: Vec3,
    ) = OverlayFlight(
        shotId = id,
        club = "driver",
        colorIndex = 0,
        trajectory =
            FlightTrajectory(
                eventId = id,
                points = listOf(FlightPoint(0.0, Vec3.ZERO, Vec3.ZERO), FlightPoint(1.0, landing, Vec3.ZERO)),
                provenance = FlightInputProvenance(),
            ),
    )

    private fun straightTrajectory(pointCount: Int): FlightTrajectory =
        FlightTrajectory(
            eventId = "s",
            points =
                (0 until pointCount).map { i ->
                    val t = i.toDouble() / (pointCount - 1)
                    FlightPoint(t, Vec3(0.0, 0.0, 100.0 * t), Vec3(0.0, 0.0, 30.0))
                },
            provenance = FlightInputProvenance(),
        )
}

internal fun storedShot(
    id: Long,
    eventId: String? = eventIdFor(id),
    sessionId: String = "s1",
    timestamp: String = "2026-09-25T10:00:${id.toString().padStart(2, '0')}",
    club: String = "driver",
    detail: ShotDetail =
        ShotDetail(
            timestamp = timestamp,
            club = club,
            ballSpeedMph = 150.0,
            estimatedCarryYards = 200.0,
            launchAngleVertical = 12.0,
            spinRpm = 2_500.0,
        ),
): HistoryShot = HistoryShot(id = id, sessionId = sessionId, eventId = eventId, detail = detail)

/** A UUID-shaped SSE/BLE event id for stored row [id]. */
internal fun eventIdFor(id: Long): String = "C0FFEE00-7950-4D7E-9DD5-" + id.toString().padStart(12, '0')
