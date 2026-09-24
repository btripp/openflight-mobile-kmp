// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.ShotEvent

/**
 * Test fixtures ported from `ios/OpenFlightTests/DrivingRangeTestFixtures.swift`.
 */
private const val DEFAULT_EVENT_ID = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1"
private const val DEFAULT_BALL_SPEED_MPH = 151.4
private const val DEFAULT_CARRY_YARDS = 264.0
private const val DEFAULT_LAUNCH_ANGLE = 12.6
private const val DEFAULT_HORIZONTAL_LAUNCH = -1.3
private const val DEFAULT_SPIN_RPM = 2_380.0
private const val DEFAULT_SPIN_AXIS = -3.4
private const val DEFAULT_CLUB_SPEED_MPH = 103.2
private const val DEFAULT_SMASH_FACTOR = 1.47
private const val DEFAULT_CLUB_PATH_DEG = 2.1

internal fun makeDrivingRangeShot(
    eventId: String = DEFAULT_EVENT_ID,
    club: String = "driver",
    ballSpeedMph: Double = DEFAULT_BALL_SPEED_MPH,
    carryYards: Double = DEFAULT_CARRY_YARDS,
    launchAngle: Double? = DEFAULT_LAUNCH_ANGLE,
    horizontalLaunch: Double? = DEFAULT_HORIZONTAL_LAUNCH,
    spinRpm: Double? = DEFAULT_SPIN_RPM,
    spinAxis: Double? = DEFAULT_SPIN_AXIS,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = eventId,
        timestamp = "2026-08-06T01:00:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = DEFAULT_CLUB_SPEED_MPH,
        smashFactor = DEFAULT_SMASH_FACTOR,
        estimatedCarryYards = carryYards,
        launchAngleVertical = launchAngle,
        launchAngleHorizontal = horizontalLaunch,
        spinRpm = spinRpm,
        clubPathDeg = DEFAULT_CLUB_PATH_DEG,
        spinAxisDeg = spinAxis,
    )

private val ZERO_VELOCITY_ENTRY = Vec3(0.0, 10.0, 40.0)
private val ZERO_VELOCITY_EXIT = Vec3(0.0, -10.0, 30.0)

internal fun makeTestTrajectory(input: FlightInput): FlightTrajectory =
    FlightTrajectory(
        eventId = input.eventId,
        id = input.eventId,
        points =
            listOf(
                FlightPoint(time = 0.0, positionMeters = Vec3.ZERO, velocityMetersPerSecond = ZERO_VELOCITY_ENTRY),
                FlightPoint(
                    time = 1.0,
                    positionMeters = Vec3(0.0, 0.0, input.targetCarryMeters),
                    velocityMetersPerSecond = ZERO_VELOCITY_EXIT,
                ),
            ),
        provenance = input.provenance,
    )
