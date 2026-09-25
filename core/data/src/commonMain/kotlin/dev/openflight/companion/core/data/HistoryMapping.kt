// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.SessionEntity
import dev.openflight.companion.core.database.SessionSummaryRow
import dev.openflight.companion.core.database.ShotEntity
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.serialization.json.Json

/** Row ↔ model mapping for [DefaultShotHistoryRepository] (plan R8h). */
private val HistoryJson = Json { encodeDefaults = false }

/** The server leaves an unset profile as `""`; stored verbatim it would be a profile you could filter on. */
private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/** A live Socket.IO shot as a row (the session id is set by the DAO). */
internal fun PiLiveShot.toEntity(): ShotEntity =
    detail.toEntity(
        eventId = null,
        rawJson = rawJson ?: HistoryJson.encodeToString(ShotDetail.serializer(), detail),
    )

/** An SSE/BLE shot as a row, merged with its Socket.IO [detail] when already known. */
internal fun ShotEvent.toEntity(detail: ShotDetail?): ShotEntity {
    if (detail != null) {
        return detail.toEntity(
            eventId = eventId,
            rawJson = HistoryJson.encodeToString(ShotDetail.serializer(), detail),
            fallback = this,
        )
    }
    return ShotEntity(
        sessionId = "",
        timestamp = timestamp,
        eventId = eventId,
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = clubSpeedMph,
        smashFactor = smashFactor,
        estimatedCarryYards = estimatedCarryYards,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        clubPathDeg = clubPathDeg,
        spinAxisDeg = spinAxisDeg,
        spinRpm = spinRpm,
        hasDetail = false,
        rawJson = HistoryJson.encodeToString(ShotEvent.serializer(), this),
    )
}

/**
 * An imported session's shots as rows: the sharer's profile is dropped (it names a profile on their
 * Pi, not this one's), and a shot number seen earlier in the file is dropped from the later shot so
 * the per-session unique index can't reject the whole import.
 */
internal fun List<ShotDetail>.toImportedEntities(): List<ShotEntity> {
    val seenNumbers = mutableSetOf<Int>()
    return map { shot ->
        val number = shot.shotNumber?.takeIf { seenNumbers.add(it) }
        val stripped = shot.copy(shotNumber = number, profileId = null, profileName = null)
        stripped.toEntity(eventId = null, rawJson = HistoryJson.encodeToString(ShotDetail.serializer(), stripped))
    }
}

private fun ShotDetail.toEntity(
    eventId: String?,
    rawJson: String,
    fallback: ShotEvent? = null,
): ShotEntity =
    ShotEntity(
        sessionId = "",
        shotNumber = shotNumber,
        timestamp = timestamp,
        eventId = eventId,
        club = club?.takeIf { it.isNotEmpty() } ?: fallback?.club.orEmpty(),
        profileId = profileId.orNullIfBlank(),
        profileName = profileName.orNullIfBlank(),
        mode = mode,
        ballSpeedMph = ballSpeedMph ?: fallback?.ballSpeedMph,
        clubSpeedMph = clubSpeedMph ?: fallback?.clubSpeedMph,
        smashFactor = smashFactor ?: fallback?.smashFactor,
        estimatedCarryYards = estimatedCarryYards ?: fallback?.estimatedCarryYards,
        carrySpinAdjusted = carrySpinAdjusted,
        carryRangeLow = carryRangeLow,
        carryRangeHigh = carryRangeHigh,
        launchAngleVertical = launchAngleVertical ?: fallback?.launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal ?: fallback?.launchAngleHorizontal,
        launchAngleConfidence = launchAngleConfidence,
        angleSource = angleSource,
        clubAngleDeg = clubAngleDeg,
        clubPathDeg = clubPathDeg ?: fallback?.clubPathDeg,
        spinAxisDeg = spinAxisDeg ?: fallback?.spinAxisDeg,
        spinRpm = spinRpm ?: fallback?.spinRpm,
        spinSource = spinSource,
        spinQuality = spinQuality,
        peakMagnitude = peakMagnitude,
        swingSpeedDurationMs = swingSpeedDurationMs,
        swingSpeedReadingCount = swingSpeedReadingCount,
        swingSpeedTriggerMph = swingSpeedTriggerMph,
        trainingImplement = trainingImplement,
        trainingImplementLabel = trainingImplementLabel,
        hasDetail = true,
        rawJson = rawJson,
    )

internal fun ShotEntity.toHistoryShot(): HistoryShot =
    HistoryShot(
        id = id,
        sessionId = sessionId,
        eventId = eventId,
        detail =
            ShotDetail(
                timestamp = timestamp,
                shotNumber = shotNumber,
                ballSpeedMph = ballSpeedMph,
                clubSpeedMph = clubSpeedMph,
                smashFactor = smashFactor,
                estimatedCarryYards = estimatedCarryYards,
                carryRange = listOfNotNull(carryRangeLow, carryRangeHigh).takeIf { it.size == 2 },
                club = club,
                profileId = profileId,
                profileName = profileName,
                peakMagnitude = peakMagnitude,
                launchAngleVertical = launchAngleVertical,
                launchAngleHorizontal = launchAngleHorizontal,
                launchAngleConfidence = launchAngleConfidence,
                angleSource = angleSource,
                clubAngleDeg = clubAngleDeg,
                clubPathDeg = clubPathDeg,
                spinAxisDeg = spinAxisDeg,
                spinRpm = spinRpm,
                spinSource = spinSource,
                spinQuality = spinQuality,
                carrySpinAdjusted = carrySpinAdjusted,
                mode = mode,
                swingSpeedDurationMs = swingSpeedDurationMs,
                swingSpeedReadingCount = swingSpeedReadingCount,
                swingSpeedTriggerMph = swingSpeedTriggerMph,
                trainingImplement = trainingImplement,
                trainingImplementLabel = trainingImplementLabel,
            ),
        starred = starred,
        note = note,
    )

internal fun SessionSummaryRow.toHistorySession(): HistorySession =
    HistorySession(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        host = host,
        transport = TransportType.entries.firstOrNull { it.name == transport },
        shotCount = shotCount,
        firstShotAt = firstShotAt,
        lastShotAt = lastShotAt,
        source = if (source == SessionEntity.SOURCE_IMPORTED) SessionSource.IMPORTED else SessionSource.LOCAL,
        ownerName = ownerName,
        title = title,
        includeInStats = includeInStats,
        note = note,
    )
