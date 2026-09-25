// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail

/** A valid UUID event id that encodes [number], so assertions stay readable. */
internal fun shotId(number: Int): String = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')

/** A distinct timestamp per [number], the Pi's session key. */
internal fun timestamp(number: Int): String = "2026-09-24T15:38:${number.toString().padStart(2, '0')}.000001"

internal fun shot(
    number: Int,
    club: String = "driver",
    ballSpeedMph: Double = 140.0,
    carryYards: Double = 250.0,
    launchAngleHorizontal: Double? = null,
    spinAxisDeg: Double? = null,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = shotId(number),
        timestamp = timestamp(number),
        club = club,
        ballSpeedMph = ballSpeedMph,
        estimatedCarryYards = carryYards,
        launchAngleHorizontal = launchAngleHorizontal,
        spinAxisDeg = spinAxisDeg,
    )

internal fun detail(
    number: Int,
    club: String = "driver",
    ballSpeedMph: Double = 140.0,
    profileName: String = "Profile 1",
    profileId: String = "p1",
): ShotDetail =
    ShotDetail(
        timestamp = timestamp(number),
        ballSpeedMph = ballSpeedMph,
        estimatedCarryYards = 250.0,
        carryRange = listOf(240.0, 260.0),
        club = club,
        profileName = profileName,
        profileId = profileId,
        launchAngleVertical = 12.0,
        launchAngleConfidence = 0.5,
    )

internal fun swingRep(
    number: Int,
    speedMph: Double,
): ShotDetail =
    ShotDetail(
        timestamp = timestamp(number),
        ballSpeedMph = speedMph,
        clubSpeedMph = speedMph,
        club = "Swing Speed",
        profileName = "Ann",
        peakMagnitude = 210.5,
        mode = "swing-speed",
        swingSpeedReadingCount = 5,
        swingSpeedTriggerMph = 80.1,
        swingSpeedDurationMs = 1200.0,
        trainingImplement = "stack-100g",
        trainingImplementLabel = "Stack 100g",
    )
