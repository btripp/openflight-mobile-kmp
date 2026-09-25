// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ProfilesSnapshot
import dev.openflight.companion.core.model.pi.SessionCleared
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.protocol.SchemaV2Event

/**
 * Plan R8e: a schema v2 event from BLE (or SSE) as the [PiEvent] the Socket.IO path already
 * produces, so both feed the same [PiSessionStore] flows. `session_cleared` over BLE names only the
 * profile (no remaining rows), which the store treats as "rows unknown".
 */
internal fun SchemaV2Event.toPiEvent(): PiEvent =
    when (this) {
        is SchemaV2Event.Profiles -> PiEvent.Profiles(ProfilesSnapshot(profiles, activeProfileId))
        is SchemaV2Event.SessionCleared -> PiEvent.Cleared(SessionCleared(profileId, shots = null))
        is SchemaV2Event.ShotDeleted -> PiEvent.ShotDeleted(timestamp)
        is SchemaV2Event.ShotProcessing -> PiEvent.Processing(state)
        is SchemaV2Event.Power -> PiEvent.Power(status)
    }

/**
 * A v2 shot's fields as the [ShotDetail] the enrichment index ([PiSessionRepository.detailFor])
 * holds, so the profile, carry range, spin source and launch confidence show over BLE too. A v1
 * shot carries none of them and gives `null`.
 */
internal fun ShotEvent.toShotDetail(): ShotDetail? =
    if (schemaVersion < 2) {
        null
    } else {
        ShotDetail(
            timestamp = timestamp,
            shotNumber = shotNumber,
            ballSpeedMph = ballSpeedMph,
            clubSpeedMph = clubSpeedMph,
            smashFactor = smashFactor,
            estimatedCarryYards = estimatedCarryYards,
            carryRange = carryRange,
            club = club,
            profileId = profileId,
            profileName = profileName,
            launchAngleVertical = launchAngleVertical,
            launchAngleHorizontal = launchAngleHorizontal,
            launchAngleConfidence = launchAngleConfidence,
            clubPathDeg = clubPathDeg,
            spinAxisDeg = spinAxisDeg,
            spinRpm = spinRpm,
            spinSource = spinSource,
        )
    }
