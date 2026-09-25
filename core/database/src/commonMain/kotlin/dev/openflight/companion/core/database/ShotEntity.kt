// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One stored shot (plan R8h, ported from the Expo app's `storage/shotRepository.ts`).
 *
 * The typed columns are the measurements the Session list, stats and CSV show; [rawJson] keeps the
 * whole payload the phone received (the Pi's `shot_to_dict`, or the SSE/BLE shot event when no
 * Socket.IO detail was seen), so a field a later version wants to show needs no migration just to
 * have been kept. `null` always means "not measured", never 0.
 *
 * Identity, in upsert order ([ShotHistoryDao.upsert]):
 * 1. `(session_id, shot_number)`: the Pi's per-run sequence, shared by `shot` and `shot_update`.
 * 2. `(session_id, event_id)`: the SSE/BLE event id.
 * 3. `(session_id, timestamp)`, only to pair an SSE/BLE row with the Socket.IO row of the same shot
 *    (see [pairsWith]); two shots from the same channel never merge on the timestamp.
 *
 * The unique indexes on `(session_id, shot_number)` and `(session_id, event_id)` hold only where
 * the value is set: SQLite treats every `NULL` as distinct in a unique index, so unnumbered shots
 * (swing-speed reps) never collide. Room can't declare a partial index (`WHERE shot_number IS NOT
 * NULL`), and doesn't need to.
 *
 * @property hasDetail whether any of this row's data came from the Pi's Socket.IO detail
 *   (`shot`/`shot_update`), which carries the profile, shot number and enrichment.
 */
@Entity(
    tableName = "shots",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["session_id", "timestamp"],
            orders = [Index.Order.ASC, Index.Order.DESC],
            name = "index_shots_session_id_timestamp",
        ),
        Index(value = ["session_id", "shot_number"], unique = true),
        Index(value = ["session_id", "event_id"], unique = true),
        Index(value = ["club"]),
        Index(value = ["profile_id"]),
    ],
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "shot_number") val shotNumber: Int? = null,
    /** The Pi's naive local ISO time; also the key `delete_shot` takes. */
    val timestamp: String,
    @ColumnInfo(name = "event_id") val eventId: String? = null,
    /** The wire club (`"7-iron"`); empty when the payload had none. */
    val club: String = "",
    /** `NULL`, never `""`: the server's empty profile is not a profile you could filter on. */
    @ColumnInfo(name = "profile_id") val profileId: String? = null,
    @ColumnInfo(name = "profile_name") val profileName: String? = null,
    val mode: String? = null,
    @ColumnInfo(name = "ball_speed_mph") val ballSpeedMph: Double? = null,
    @ColumnInfo(name = "club_speed_mph") val clubSpeedMph: Double? = null,
    @ColumnInfo(name = "smash_factor") val smashFactor: Double? = null,
    @ColumnInfo(name = "estimated_carry_yards") val estimatedCarryYards: Double? = null,
    @ColumnInfo(name = "carry_spin_adjusted") val carrySpinAdjusted: Double? = null,
    @ColumnInfo(name = "carry_range_low") val carryRangeLow: Double? = null,
    @ColumnInfo(name = "carry_range_high") val carryRangeHigh: Double? = null,
    @ColumnInfo(name = "launch_angle_vertical") val launchAngleVertical: Double? = null,
    @ColumnInfo(name = "launch_angle_horizontal") val launchAngleHorizontal: Double? = null,
    @ColumnInfo(name = "launch_angle_confidence") val launchAngleConfidence: Double? = null,
    @ColumnInfo(name = "angle_source") val angleSource: String? = null,
    @ColumnInfo(name = "club_angle_deg") val clubAngleDeg: Double? = null,
    @ColumnInfo(name = "club_path_deg") val clubPathDeg: Double? = null,
    @ColumnInfo(name = "spin_axis_deg") val spinAxisDeg: Double? = null,
    @ColumnInfo(name = "spin_rpm") val spinRpm: Double? = null,
    @ColumnInfo(name = "spin_source") val spinSource: String? = null,
    @ColumnInfo(name = "spin_quality") val spinQuality: String? = null,
    @ColumnInfo(name = "peak_magnitude") val peakMagnitude: Double? = null,
    @ColumnInfo(name = "swing_speed_duration_ms") val swingSpeedDurationMs: Double? = null,
    @ColumnInfo(name = "swing_speed_reading_count") val swingSpeedReadingCount: Int? = null,
    @ColumnInfo(name = "swing_speed_trigger_mph") val swingSpeedTriggerMph: Double? = null,
    @ColumnInfo(name = "training_implement") val trainingImplement: String? = null,
    @ColumnInfo(name = "training_implement_label") val trainingImplementLabel: String? = null,
    @ColumnInfo(name = "has_detail") val hasDetail: Boolean = false,
    @ColumnInfo(name = "raw_json") val rawJson: String,
) {
    /**
     * Whether [incoming] is this row's shot arriving on the **other** channel, matched on the
     * timestamp: exactly one of the two carries an SSE/BLE event id, and their shot numbers don't
     * contradict each other. Two unnumbered Socket.IO rows (or two SSE events) sharing a timestamp
     * stay two shots, as in Expo's "appends a shot the server could not number".
     */
    fun pairsWith(incoming: ShotEntity): Boolean =
        timestamp == incoming.timestamp &&
            (eventId == null) != (incoming.eventId == null) &&
            (shotNumber == null || incoming.shotNumber == null || shotNumber == incoming.shotNumber)

    /**
     * This row updated with [incoming]: every measurement [incoming] carries replaces the stored
     * one, and one it lacks keeps the stored value (a Socket.IO detail and an SSE event each know
     * things the other doesn't). The row keeps its id and session; [rawJson] prefers the Socket.IO
     * payload, the richer one.
     */
    @Suppress("CyclomaticComplexMethod") // One elvis per column.
    fun mergedWith(incoming: ShotEntity): ShotEntity =
        ShotEntity(
            id = id,
            sessionId = sessionId,
            shotNumber = incoming.shotNumber ?: shotNumber,
            timestamp = timestamp,
            eventId = incoming.eventId ?: eventId,
            club = incoming.club.ifEmpty { club },
            profileId = incoming.profileId ?: profileId,
            profileName = incoming.profileName ?: profileName,
            mode = incoming.mode ?: mode,
            ballSpeedMph = incoming.ballSpeedMph ?: ballSpeedMph,
            clubSpeedMph = incoming.clubSpeedMph ?: clubSpeedMph,
            smashFactor = incoming.smashFactor ?: smashFactor,
            estimatedCarryYards = incoming.estimatedCarryYards ?: estimatedCarryYards,
            carrySpinAdjusted = incoming.carrySpinAdjusted ?: carrySpinAdjusted,
            carryRangeLow = incoming.carryRangeLow ?: carryRangeLow,
            carryRangeHigh = incoming.carryRangeHigh ?: carryRangeHigh,
            launchAngleVertical = incoming.launchAngleVertical ?: launchAngleVertical,
            launchAngleHorizontal = incoming.launchAngleHorizontal ?: launchAngleHorizontal,
            launchAngleConfidence = incoming.launchAngleConfidence ?: launchAngleConfidence,
            angleSource = incoming.angleSource ?: angleSource,
            clubAngleDeg = incoming.clubAngleDeg ?: clubAngleDeg,
            clubPathDeg = incoming.clubPathDeg ?: clubPathDeg,
            spinAxisDeg = incoming.spinAxisDeg ?: spinAxisDeg,
            spinRpm = incoming.spinRpm ?: spinRpm,
            spinSource = incoming.spinSource ?: spinSource,
            spinQuality = incoming.spinQuality ?: spinQuality,
            peakMagnitude = incoming.peakMagnitude ?: peakMagnitude,
            swingSpeedDurationMs = incoming.swingSpeedDurationMs ?: swingSpeedDurationMs,
            swingSpeedReadingCount = incoming.swingSpeedReadingCount ?: swingSpeedReadingCount,
            swingSpeedTriggerMph = incoming.swingSpeedTriggerMph ?: swingSpeedTriggerMph,
            trainingImplement = incoming.trainingImplement ?: trainingImplement,
            trainingImplementLabel = incoming.trainingImplementLabel ?: trainingImplementLabel,
            hasDetail = hasDetail || incoming.hasDetail,
            rawJson = if (incoming.hasDetail || !hasDetail) incoming.rawJson else rawJson,
        )
}
