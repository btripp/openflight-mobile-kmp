// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One completed shot from the Pi, ported from `ios/OpenFlight/ShotEvent.swift`.
 *
 * `schema_version, event_id, timestamp, club, ball_speed_mph, estimated_carry_yards` are
 * required; every other measurement is nullable because not every hardware configuration can
 * produce it. `eventId` stays a `String` (validated as a UUID on decode) instead of a UUID type
 * so this model doesn't depend on `kotlin.uuid` stability.
 *
 * Schema v2 (BLE v2 shot characteristic, SSE `?schema=2`; backend `docs/ios-ble.md` "v2 shot")
 * adds the fields from [type] on. A v1 payload leaves them all `null`. Every v2 key is always
 * present on the wire, `null` when unknown. A provisional shot ([isProvisional]) and its final
 * version share one [eventId], so consumers upsert by it.
 */
@Serializable
data class ShotEvent(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("event_id") val eventId: String,
    val timestamp: String,
    val club: String,
    @SerialName("ball_speed_mph") val ballSpeedMph: Double,
    @SerialName("club_speed_mph") val clubSpeedMph: Double? = null,
    @SerialName("smash_factor") val smashFactor: Double? = null,
    @SerialName("estimated_carry_yards") val estimatedCarryYards: Double,
    @SerialName("launch_angle_vertical") val launchAngleVertical: Double? = null,
    @SerialName("launch_angle_horizontal") val launchAngleHorizontal: Double? = null,
    @SerialName("spin_rpm") val spinRpm: Double? = null,
    @SerialName("club_path_deg") val clubPathDeg: Double? = null,
    @SerialName("spin_axis_deg") val spinAxisDeg: Double? = null,
    /** v2: always `"shot"`. */
    val type: String? = null,
    /** v2: `false` for the OPS-only provisional shot, `true` for the final one; `null` on v1. */
    val final: Boolean? = null,
    /** v2: the per-monitor-run sequence; not reused after a delete. */
    @SerialName("shot_number") val shotNumber: Int? = null,
    /** v2: the profile the shot was filed under at detection. */
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    /** v2: `[low, high]` carry estimate in yards. */
    @SerialName("carry_range") val carryRange: List<Double>? = null,
    /** v2: where [spinRpm] came from. */
    @SerialName("spin_source") val spinSource: String? = null,
    /** v2: 0–1. */
    @SerialName("launch_angle_confidence") val launchAngleConfidence: Double? = null,
    /** v2: optional-hardware progress; `null` when the shot never waited for it. */
    val enrichment: EnrichmentProgress? = null,
) {
    init {
        require(UUID_REGEX.matches(eventId)) { "eventId must be a UUID string, was: $eventId" }
    }

    /** A v2 provisional shot that a final version with the same [eventId] will replace. */
    val isProvisional: Boolean
        get() = final == false

    /**
     * The club to show: a known club's display name ("pw" → "Pitching Wedge"), otherwise `club`
     * with underscores turned into spaces and Swift-`.capitalized`-style casing
     * ([GolfClub.displayNameFor]).
     */
    val displayClub: String
        get() = GolfClub.displayNameFor(club)
}

/**
 * A v2 shot's `enrichment`: `pending` on a provisional shot; `complete`, or `skipped` with a
 * [reason] (`deadline`, `capacity`, `queue_full`, `worker_unavailable`), on its final version.
 */
@Serializable
data class EnrichmentProgress(
    val status: String,
    val reason: String? = null,
)

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Mirrors Swift's `String.capitalized`: the first letter of every letter run is upper-cased,
 * every other letter is lower-cased, and non-letter characters (spaces, hyphens, digits) are
 * left alone but count as word boundaries. `"7-iron"` becomes `"7-Iron"`, `"3-wood"` becomes
 * `"3-Wood"`.
 */
internal fun capitalizedWords(value: String): String {
    val builder = StringBuilder(value.length)
    var capitalizeNext = true
    for (char in value) {
        if (char.isLetter()) {
            builder.append(if (capitalizeNext) char.uppercaseChar() else char.lowercaseChar())
            capitalizeNext = false
        } else {
            builder.append(char)
            capitalizeNext = !char.isLetterOrDigit()
        }
    }
    return builder.toString()
}
