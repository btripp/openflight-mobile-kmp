// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.swingSpeedMph
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail

/** Rows for the phone's own history (newest first), enriched from the Pi's details when known. */
internal fun localRows(
    history: List<ShotEvent>,
    details: Map<String, ShotDetail>,
): List<SessionShotRow> =
    history.mapIndexed { index, shot ->
        val detail = details[shot.timestamp]
        SessionShotRow(
            id = shot.eventId,
            shotNumber = history.size - index,
            timestamp = shot.timestamp,
            club = shot.club,
            playerName = detail?.playerName,
            ballSpeedMph = shot.ballSpeedMph,
            clubSpeedMph = shot.clubSpeedMph,
            launchAngleVerticalDeg = shot.launchAngleVertical,
            spinRpm = shot.spinRpm,
            carryYards = shot.estimatedCarryYards,
            isSwingSpeed = detail?.isSwingSpeed ?: false,
            swingSpeedMph = detail?.takeIf { it.isSwingSpeed }?.swingSpeedMph,
            readingCount = detail?.swingSpeedReadingCount,
            triggerSpeedMph = detail?.swingSpeedTriggerMph,
            durationMs = detail?.swingSpeedDurationMs,
            implementLabel = detail?.trainingImplementLabel,
            enrichment = detail?.let(ShotEnrichment::from),
        )
    }

/** Rows for the Pi's session (newest first), keyed by timestamp. */
internal fun piRows(session: List<ShotDetail>): List<SessionShotRow> =
    session.mapIndexed { index, detail ->
        SessionShotRow(
            id = detail.timestamp,
            shotNumber = session.size - index,
            timestamp = detail.timestamp,
            club = detail.club.orEmpty(),
            playerName = detail.playerName,
            ballSpeedMph = detail.ballSpeedMph,
            clubSpeedMph = detail.clubSpeedMph,
            launchAngleVerticalDeg = detail.launchAngleVertical,
            spinRpm = detail.spinRpm,
            carryYards = detail.estimatedCarryYards,
            isSwingSpeed = detail.isSwingSpeed,
            swingSpeedMph = detail.takeIf { it.isSwingSpeed }?.swingSpeedMph,
            readingCount = detail.swingSpeedReadingCount,
            triggerSpeedMph = detail.swingSpeedTriggerMph,
            durationMs = detail.swingSpeedDurationMs,
            implementLabel = detail.trainingImplementLabel,
            enrichment = ShotEnrichment.from(detail),
        )
    }
