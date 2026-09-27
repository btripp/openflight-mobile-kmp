// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.EnrichmentProgress
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ProfilesSnapshot
import dev.openflight.companion.core.model.pi.SessionCleared
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.protocol.SchemaV2Event
import okio.ByteString.Companion.toByteString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

/**
 * Plan R8j: a live Socket.IO `shot`/`shot_update` as the schema v2 [ShotEvent] the fork's SSE
 * stream would have sent for it (`build_shot_event_v2` over the same `shot_to_dict` row, backend
 * `openflight-ble` `ble/protocol.py`), so a stock Pi's shots reach `ShotRepository.history` in the
 * same shape. The inverse of [toShotDetail].
 *
 * - [ShotEvent.eventId] is the fork's `stable_shot_event_id` (UUIDv5 of
 *   `"<timestamp>#<shot_number>"`), so a shot and its `shot_update` share one id and upsert in
 *   place, exactly as on the fork.
 * - A `shot` whose `pending` hardware is still enriching it is provisional (`final = false`); its
 *   `shot_update`, and any `shot` with nothing pending, is final.
 * - `null` for a swing-speed rep (not a ball flight) or a row without the SSE's required ball
 *   speed or carry.
 */
internal fun PiLiveShot.toShotEvent(): ShotEvent? {
    val shot = detail
    val ballSpeed = shot.ballSpeedMph
    val carry = shot.estimatedCarryYards
    if (shot.isSwingSpeed || ballSpeed == null || carry == null) return null
    val provisional = provisional && !isUpdate
    return ShotEvent(
        schemaVersion = LIVE_SHOT_SCHEMA_VERSION,
        eventId = stableShotEventId(shot.timestamp, shot.shotNumber),
        timestamp = shot.timestamp,
        club = shot.club.orEmpty(),
        ballSpeedMph = ballSpeed,
        clubSpeedMph = shot.clubSpeedMph,
        smashFactor = shot.smashFactor,
        estimatedCarryYards = carry,
        launchAngleVertical = shot.launchAngleVertical,
        launchAngleHorizontal = shot.launchAngleHorizontal,
        spinRpm = shot.spinRpm,
        clubPathDeg = shot.clubPathDeg,
        spinAxisDeg = shot.spinAxisDeg,
        type = "shot",
        final = !provisional,
        shotNumber = shot.shotNumber,
        profileId = shot.profileId?.takeIf { it.isNotEmpty() },
        profileName = shot.profileName?.takeIf { it.isNotEmpty() },
        carryRange = shot.carryRange,
        spinSource = shot.spinSource?.takeIf { it.isNotEmpty() },
        launchAngleConfidence = shot.launchAngleConfidence,
        enrichment = if (provisional) EnrichmentProgress(ENRICHMENT_PENDING) else enrichment,
    )
}

/**
 * The fork backend's `stable_shot_event_id`: `uuid.uuid5(namespace, f"{timestamp}#{shot_number}")`,
 * where a missing number prints as Python's `None`.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun stableShotEventId(
    timestamp: String,
    shotNumber: Int?,
): String {
    val name = "$timestamp#${shotNumber?.toString() ?: "None"}"
    val hash = (SHOT_EVENT_NAMESPACE + name.encodeToByteArray()).toByteString().sha1().toByteArray()
    val bytes = hash.copyOf(UUID_BYTES)
    bytes[UUID_VERSION_BYTE] = ((bytes[UUID_VERSION_BYTE].toInt() and LOW_NIBBLE) or UUID_VERSION_5).toByte()
    bytes[UUID_VARIANT_BYTE] = ((bytes[UUID_VARIANT_BYTE].toInt() and LOW_SIX_BITS) or UUID_VARIANT_RFC4122).toByte()
    return Uuid.fromByteArray(bytes).toString()
}

/**
 * Where a `shot_update` for ([shotNumber], [timestamp]) belongs in a newest-first list: the first
 * row with that `shot_number` (Expo `replaceShot`), else the first with that `timestamp` (the web
 * UI's key; a swing-speed row has no number), else `-1`.
 */
internal fun <T> List<T>.indexOfShot(
    shotNumber: Int?,
    timestamp: String,
    numberOf: (T) -> Int?,
    timestampOf: (T) -> String,
): Int {
    val byNumber = shotNumber?.let { number -> indexOfFirst { numberOf(it) == number } } ?: -1
    return if (byNumber >= 0) byNumber else indexOfFirst { timestampOf(it) == timestamp }
}

private const val LIVE_SHOT_SCHEMA_VERSION = 2
private const val ENRICHMENT_PENDING = "pending"
private const val UUID_BYTES = 16
private const val UUID_VERSION_BYTE = 6
private const val UUID_VARIANT_BYTE = 8
private const val LOW_NIBBLE = 0x0f
private const val LOW_SIX_BITS = 0x3f
private const val UUID_VERSION_5 = 0x50
private const val UUID_VARIANT_RFC4122 = 0x80

/** `_SHOT_EVENT_NAMESPACE = uuid.UUID("D49C99A9-A305-49CA-A8C2-7D30B7645988")` (fork `ble/protocol.py:54`). */
private val SHOT_EVENT_NAMESPACE: ByteArray =
    "D49C99A9A30549CAA8C27D30B7645988".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
