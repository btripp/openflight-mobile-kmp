// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.GolfClub

/** A metric [CalloutComposer] can speak, in the order the plan F4 spec lists them. */
enum class CalloutField {
    CARRY,
    TOTAL,
    BALL_SPEED,
    CLUB_SPEED,
    SMASH,
    LAUNCH,
    SPIN,
    OFFLINE,
    CLUB,
    TARGET_DELTA,
}

/** How much a call-out says besides the numbers themselves. */
enum class CalloutVerbosity {
    /** Values only, e.g. `"152 yards, 118 miles per hour"`. */
    CONCISE,

    /** Each value prefixed with its field name, e.g. `"Carry 152 yards, ball speed 118 miles per hour"`. */
    DETAILED,
}

/**
 * The plain, callout-composer-only view of a shot's metrics (plan F4). Every value is in the same
 * imperial base units as the rest of the wire ([UnitSystem]'s doc): yards, miles per hour,
 * degrees, rpm. [CalloutComposer] converts to the user's [UnitSystem] itself.
 *
 * A `null` field is left out of the call-out entirely — [CalloutComposer] never speaks "null" or
 * a fabricated zero for a measurement that wasn't taken. [estimated] names which of the non-null
 * fields came from an estimate (a resolved/missing measurement, or an F2 conditions adjustment),
 * so the composer can prefix them "about".
 *
 * This type intentionally knows nothing about `core:flight` or `ShotEvent` (plan F4 amendment):
 * callers (the `shared` call-out coordinator, F7) translate their richer shot/estimate types into
 * this one, so `core:insights` stays free of a `core:flight` dependency.
 *
 * @property offlineYards signed lateral miss, matching the flight sim's `x = lateral right`
 *   convention (F2 §0.2): positive is right of the target line, negative is left.
 * @property targetDeltaYards signed distance from a game target: positive means the shot carried
 *   past it ("long"), negative means it fell short.
 */
data class CalloutInput(
    val carryYards: Double? = null,
    val totalYards: Double? = null,
    val ballSpeedMph: Double? = null,
    val clubSpeedMph: Double? = null,
    val smash: Double? = null,
    val launchAngleDegrees: Double? = null,
    val spinRpm: Double? = null,
    val offlineYards: Double? = null,
    val club: GolfClub? = null,
    val targetDeltaYards: Double? = null,
    val estimated: Set<CalloutField> = emptySet(),
)
