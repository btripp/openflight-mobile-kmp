// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.EstimatedTotal
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.swingSpeedMph
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
            profileName = detail?.profileName?.takeIf { it.isNotBlank() },
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

/** What the phone's own shots fly with (keyed like [localRows]); swing reps known from the Pi are left out. */
internal fun localFlights(
    history: List<ShotEvent>,
    details: Map<String, ShotDetail>,
): List<FlightMeasurements> =
    history.mapNotNull { shot ->
        val detail = details[shot.timestamp]
        when {
            detail?.isSwingSpeed == true -> null

            // The Pi's row adds the spin-adjusted carry, the estimate's preferred anchor.
            detail != null -> detail.toFlightMeasurements()?.copy(id = shot.eventId) ?: shot.toFlightMeasurements()

            else -> shot.toFlightMeasurements()
        }
    }

/** The rows' units plus what plan F5b's estimated totals need: the conditions and "Show total distance". */
internal data class RowDisplay(
    val units: UnitSystem,
    val conditions: Conditions,
    val targetBearing: TargetBearing?,
    val showTotal: Boolean,
)

internal fun rowDisplay(
    settings: SettingsRepository,
    conditions: ConditionsRepository,
): Flow<RowDisplay> =
    combine(settings.units, conditions.conditions, conditions.targetBearing, settings.showTotalDistance, ::RowDisplay)

/** [rows] with each one's estimated total and roll (plan F5b) from [totals], matched by id. */
internal fun List<SessionShotRow>.withTotals(
    totals: Map<String, EstimatedTotal>,
    units: UnitSystem,
): List<SessionShotRow> = map { row -> totals[row.id]?.let { row.copy(estimatedTotal = it.label(units)) } ?: row }

/** Rows for the Pi's session (newest first), keyed by timestamp. */
internal fun piRows(session: List<ShotDetail>): List<SessionShotRow> =
    session.mapIndexed { index, detail ->
        SessionShotRow(
            id = detail.timestamp,
            shotNumber = session.size - index,
            timestamp = detail.timestamp,
            club = detail.club.orEmpty(),
            profileName = detail.profileName?.takeIf { it.isNotBlank() },
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
