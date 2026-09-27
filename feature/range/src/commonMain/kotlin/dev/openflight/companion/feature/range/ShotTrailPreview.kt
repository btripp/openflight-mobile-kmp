// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.ShotFlightPlanner
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.GolfClub

/**
 * Plan F8a2t: what the Settings › Practice "Shot trail" preview draws on both platforms, through
 * the same `RangeCanvas` / `RangeCanvasView` and shared [ShotTrail] as the range: one 7-iron
 * flying on a loop under a three-quarter [VIEW], and three earlier shots for "Keep last shots".
 * The flights are simulated once (ISA, no wind), on first use.
 */
object ShotTrailPreview {
    /** The fixed tee camera swung round and down the range, so the arc (and a twist) reads from the side. */
    val VIEW: ViewTransform = ViewTransform(zoom = 1.3, panZ = -50.0, orbitYawDegrees = 50.0)

    /** The pause after each landing before the preview flies again. */
    const val LOOP_PAUSE_MILLIS: Long = 1_200L

    /** The looping shot (a 7-iron) with the given [playbackId]. */
    fun flight(playbackId: Long): ActiveFlight = shots.first().toActiveFlight(playbackId)

    /** The trail state for the picker's choice: the earlier shots only when [keepLast] asks for them. */
    fun trail(
        style: ShotTrailStyle,
        keepLast: Int,
        landingEffect: LandingEffect,
    ): RangeTrailState =
        RangeTrailState(
            style = style,
            keepLast = keepLast,
            landingEffect = landingEffect,
            priorFlights = if (keepLast > 0) priors.take(keepLast) else emptyList(),
        )

    private class PreviewShot(
        val club: GolfClub,
        val trajectory: FlightTrajectory,
        val spinRpm: Double,
    ) {
        fun toActiveFlight(playbackId: Long): ActiveFlight =
            ActiveFlight(trajectory, playbackId, spinRpm = spinRpm, clubColorIndex = club.ordinal)
    }

    private val shots: List<PreviewShot> by lazy {
        val planner = ShotFlightPlanner()
        listOf(
            Triple(GolfClub.IRON_7, doubleArrayOf(120.0, 165.0, 16.3, 0.0), 7_000.0),
            Triple(GolfClub.IRON_5, doubleArrayOf(132.0, 190.0, 13.0, -2.5), 5_300.0),
            Triple(GolfClub.PITCHING_WEDGE, doubleArrayOf(102.0, 135.0, 24.0, 3.0), 9_000.0),
            Triple(GolfClub.DRIVER, doubleArrayOf(167.0, 270.0, 11.0, 1.5), 2_700.0),
        ).mapIndexedNotNull { index, (club, numbers, spin) ->
            val measurements =
                FlightMeasurements(
                    id = "00000000-0000-4000-8000-00000000f8a$index",
                    club = club.wireValue,
                    ballSpeedMph = numbers[0],
                    carryYards = numbers[1],
                    launchAngleVertical = numbers[2],
                    launchAngleHorizontal = numbers[3],
                    spinRpm = spin,
                )
            planner.plan(measurements, Conditions.ISA, targetBearing = null)?.let {
                PreviewShot(club, it.trajectory, spin)
            }
        }
    }

    private val priors: List<ActiveFlight> by lazy {
        shots.drop(1).mapIndexed { index, shot -> shot.toActiveFlight(playbackId = -1L - index) }
    }
}
