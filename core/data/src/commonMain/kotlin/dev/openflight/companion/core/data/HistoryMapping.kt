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

/**
 * An SSE/BLE shot as a row, merged with its Socket.IO [detail] when already known.
 *
 * A schema v2 event's own values win: [detail] is looked up by timestamp on another collector
 * (the Pi session's), so over BLE it can still be the provisional's while this is the final
 * (#66), or missing altogether (#67). [detail] only fills what the event lacks (mode, spin
 * quality, angle source, …). A v1 event carries none of the v2 fields, so [detail] leads.
 */
internal fun ShotEvent.toEntity(detail: ShotDetail?): ShotEntity {
    val own = toShotDetail()
    val merged =
        when {
            own == null -> detail
            detail == null -> own
            else -> detail.overriddenBy(own)
        } ?: return toEntityWithoutDetail()
    return merged.toEntity(
        eventId = eventId,
        rawJson =
            if (detail != null) {
                HistoryJson.encodeToString(ShotDetail.serializer(), merged)
            } else {
                HistoryJson.encodeToString(ShotEvent.serializer(), this)
            },
        fallback = this,
        hasDetail = detail != null,
    )
}

/** A v1 event nothing else knows about: only the fields it carries. */
private fun ShotEvent.toEntityWithoutDetail(): ShotEntity =
    ShotEntity(
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

/** Plan F14: Demo mode's seeded shots as rows, keeping their (demo) profiles. */
internal fun List<ShotDetail>.toDemoEntities(): List<ShotEntity> =
    map { shot -> shot.toEntity(eventId = null, rawJson = HistoryJson.encodeToString(ShotDetail.serializer(), shot)) }

/**
 * This detail with every value a v2 event's [own] detail ([toShotDetail]) carries in its place;
 * the rest (the enrichment only Socket.IO sends) stays.
 */
@Suppress("CyclomaticComplexMethod") // One elvis per field, as in ShotEntity.mergedWith.
private fun ShotDetail.overriddenBy(own: ShotDetail): ShotDetail =
    copy(
        timestamp = own.timestamp,
        shotNumber = own.shotNumber ?: shotNumber,
        ballSpeedMph = own.ballSpeedMph ?: ballSpeedMph,
        clubSpeedMph = own.clubSpeedMph ?: clubSpeedMph,
        smashFactor = own.smashFactor ?: smashFactor,
        estimatedCarryYards = own.estimatedCarryYards ?: estimatedCarryYards,
        carryRange = own.carryRange ?: carryRange,
        club = own.club?.takeIf { it.isNotEmpty() } ?: club,
        profileId = own.profileId.orNullIfBlank() ?: profileId,
        profileName = own.profileName.orNullIfBlank() ?: profileName,
        launchAngleVertical = own.launchAngleVertical ?: launchAngleVertical,
        launchAngleHorizontal = own.launchAngleHorizontal ?: launchAngleHorizontal,
        launchAngleConfidence = own.launchAngleConfidence ?: launchAngleConfidence,
        clubPathDeg = own.clubPathDeg ?: clubPathDeg,
        spinAxisDeg = own.spinAxisDeg ?: spinAxisDeg,
        spinRpm = own.spinRpm ?: spinRpm,
        spinSource = own.spinSource.orNullIfBlank() ?: spinSource,
    )

private fun ShotDetail.toEntity(
    eventId: String?,
    rawJson: String,
    fallback: ShotEvent? = null,
    hasDetail: Boolean = true,
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
        hasDetail = hasDetail,
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
        source =
            when (source) {
                SessionEntity.SOURCE_IMPORTED -> SessionSource.IMPORTED
                SessionEntity.SOURCE_DEMO -> SessionSource.DEMO
                else -> SessionSource.LOCAL
            },
        ownerName = ownerName,
        title = title,
        includeInStats = includeInStats,
        note = note,
    )
