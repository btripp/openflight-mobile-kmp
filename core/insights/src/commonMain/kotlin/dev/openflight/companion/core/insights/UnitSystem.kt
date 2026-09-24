// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * The user's preferred unit system, ported from the web UI's `UnitSystem`
 * (`ui/src/utils/units.ts`): IMPERIAL renders mph/yds, METRIC renders km/h/m. Every wire value
 * from the Pi ([dev.openflight.companion.core.model.ShotEvent]) stays in the imperial units it
 * arrives in; conversion only happens for display.
 */
enum class UnitSystem {
    IMPERIAL,
    METRIC,
}

private const val MPH_TO_KMH = 1.60934
private const val YARDS_TO_METERS = 0.9144

/** Mirrors `convertSpeedFromMph` (`units.ts`). */
fun convertSpeedFromMph(
    speedMph: Double,
    unitSystem: UnitSystem,
): Double = if (unitSystem == UnitSystem.METRIC) speedMph * MPH_TO_KMH else speedMph

/** Mirrors `convertDistanceFromYards` (`units.ts`). */
fun convertDistanceFromYards(
    distanceYards: Double,
    unitSystem: UnitSystem,
): Double = if (unitSystem == UnitSystem.METRIC) distanceYards * YARDS_TO_METERS else distanceYards

/** Mirrors `getSpeedUnit` (`units.ts`). */
fun speedUnitLabel(unitSystem: UnitSystem): String = if (unitSystem == UnitSystem.METRIC) "km/h" else "mph"

/** Mirrors `getDistanceUnit` (`units.ts`). */
fun distanceUnitLabel(unitSystem: UnitSystem): String = if (unitSystem == UnitSystem.METRIC) "m" else "yds"

/**
 * `"<value> <unit>"`, converted into [unitSystem] and rounded to [digits] decimals. Mirrors
 * `formatSpeed` (`units.ts`), except the unit label is appended here instead of by the caller,
 * so every card renders the same "62.3 mph" shape (plan R5a: "speed and distance keep a space
 * before the unit").
 */
fun formatSpeed(
    speedMph: Double,
    unitSystem: UnitSystem,
    digits: Int = 1,
): String {
    val converted = convertSpeedFromMph(speedMph, unitSystem)
    return "${ShotMetricFormatter.number(converted, digits)} ${speedUnitLabel(unitSystem)}"
}

/** `"<value> <unit>"`, converted into [unitSystem] and rounded to [digits] decimals. Mirrors `formatDistance`. */
fun formatDistance(
    distanceYards: Double,
    unitSystem: UnitSystem,
    digits: Int = 0,
): String {
    val converted = convertDistanceFromYards(distanceYards, unitSystem)
    return "${ShotMetricFormatter.number(converted, digits)} ${distanceUnitLabel(unitSystem)}"
}

/**
 * `"<value>°"` with no space (plan R5a: "degrees render tight, e.g. '9.5°'"), unlike the speed and
 * distance units above.
 */
fun formatDegrees(
    valueDegrees: Double,
    digits: Int = 1,
): String = "${ShotMetricFormatter.number(valueDegrees, digits)}°"
