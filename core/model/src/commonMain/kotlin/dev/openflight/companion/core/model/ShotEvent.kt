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
) {
    init {
        require(UUID_REGEX.matches(eventId)) { "eventId must be a UUID string, was: $eventId" }
    }

    /** `club` with underscores turned into spaces, then Swift-`.capitalized`-style casing. */
    val displayClub: String
        get() = capitalizedWords(club.replace('_', ' '))
}

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
