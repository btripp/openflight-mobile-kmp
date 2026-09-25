// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The 20 clubs OpenFlight tracks, ported from `ios/OpenFlight/PhoneControl.swift`'s `GolfClub`.
 * `wireValue` is the raw string used on the wire (`set_club`/`get_club`/`club_changed` payloads
 * and shot `club` fields); `displayName` matches Swift's `.capitalized`, except for the four
 * wedges, which use explicit names.
 */
@Serializable
enum class GolfClub(
    val wireValue: String,
    val displayName: String,
) {
    @SerialName("driver")
    DRIVER("driver", "Driver"),

    @SerialName("3-wood")
    WOOD_3("3-wood", "3-Wood"),

    @SerialName("5-wood")
    WOOD_5("5-wood", "5-Wood"),

    @SerialName("7-wood")
    WOOD_7("7-wood", "7-Wood"),

    @SerialName("3-hybrid")
    HYBRID_3("3-hybrid", "3-Hybrid"),

    @SerialName("5-hybrid")
    HYBRID_5("5-hybrid", "5-Hybrid"),

    @SerialName("7-hybrid")
    HYBRID_7("7-hybrid", "7-Hybrid"),

    @SerialName("9-hybrid")
    HYBRID_9("9-hybrid", "9-Hybrid"),

    @SerialName("2-iron")
    IRON_2("2-iron", "2-Iron"),

    @SerialName("3-iron")
    IRON_3("3-iron", "3-Iron"),

    @SerialName("4-iron")
    IRON_4("4-iron", "4-Iron"),

    @SerialName("5-iron")
    IRON_5("5-iron", "5-Iron"),

    @SerialName("6-iron")
    IRON_6("6-iron", "6-Iron"),

    @SerialName("7-iron")
    IRON_7("7-iron", "7-Iron"),

    @SerialName("8-iron")
    IRON_8("8-iron", "8-Iron"),

    @SerialName("9-iron")
    IRON_9("9-iron", "9-Iron"),

    @SerialName("pw")
    PITCHING_WEDGE("pw", "Pitching Wedge"),

    @SerialName("gw")
    GAP_WEDGE("gw", "Gap Wedge"),

    @SerialName("sw")
    SAND_WEDGE("sw", "Sand Wedge"),

    @SerialName("lw")
    LOB_WEDGE("lw", "Lob Wedge"),
    ;

    /** Compact label for chart markers and legends: "D", "3W", "5H", "7i", "PW". */
    val shortLabel: String
        get() {
            val number = wireValue.substringBefore('-')
            return when {
                this == DRIVER -> "D"
                wireValue.endsWith("-wood") -> "${number}W"
                wireValue.endsWith("-hybrid") -> "${number}H"
                wireValue.endsWith("-iron") -> "${number}i"
                else -> wireValue.uppercase()
            }
        }

    companion object {
        fun fromWireValue(value: String): GolfClub? = entries.firstOrNull { it.wireValue == value }

        /**
         * The name to show for a raw wire `club` value: a known club's [displayName] ("pw" →
         * "Pitching Wedge", "7-iron" → "7-Iron"); anything else with underscores turned into
         * spaces and Swift-`.capitalized`-style casing ("iron_7" → "Iron 7"), as the reference does.
         */
        fun displayNameFor(wireValue: String): String =
            fromWireValue(wireValue)?.displayName ?: capitalizedWords(wireValue.replace('_', ' '))

        /**
         * [shortLabel] for a raw wire `club` value; unknown values (the Pi may send clubs this app
         * doesn't know yet) fall back to their first two characters.
         */
        fun shortLabelFor(wireValue: String): String =
            fromWireValue(wireValue)?.shortLabel ?: wireValue.take(SHORT_LABEL_FALLBACK_LENGTH).ifEmpty { "?" }

        private const val SHORT_LABEL_FALLBACK_LENGTH = 2
    }
}
