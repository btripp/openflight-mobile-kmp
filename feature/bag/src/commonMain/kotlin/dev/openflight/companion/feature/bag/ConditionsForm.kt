// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlin.math.abs

/**
 * The manual conditions editor's fields, as typed, in the display units ([UnitSystem.IMPERIAL]:
 * feet, °F, mph; [UnitSystem.METRIC]: metres, °C, km/h). Directions are degrees clockwise from
 * north. An empty [targetBearing] means "not set", so wind is left out (plan §0.2).
 */
data class ConditionsForm(
    val altitude: String,
    val temperature: String,
    val windSpeed: String,
    val windFrom: String,
    val surface: Firmness,
    val targetBearing: String,
) {
    /** The parsed conditions, or why they can't be saved. */
    fun parse(units: UnitSystem): ConditionsFormResult {
        val typed = typedValues()
        val metric = typed.getOrNull()?.inMetric(units)
        val error = typed.exceptionOrNull()?.message ?: metric?.let(::rangeError)
        return if (metric == null || error != null) {
            ConditionsFormResult.Invalid(error ?: CHECK_VALUES)
        } else {
            ConditionsFormResult.Valid(
                conditions =
                    Conditions(
                        altitudeMeters = metric.altitudeMeters,
                        temperatureC = metric.temperatureC,
                        wind = Wind(speedMps = metric.windMps, fromDegrees = metric.windFromDegrees % FULL_TURN),
                        surface = surface,
                        source = ConditionsSource.MANUAL,
                    ),
                targetBearing = metric.bearingDegrees?.let { TargetBearing(it % FULL_TURN) },
            )
        }
    }

    /** The typed numbers, or a failure naming the first field that isn't one. */
    private fun typedValues(): Result<Typed> =
        runCatching {
            Typed(
                altitude = altitude.number() ?: fieldError("Enter an altitude."),
                temperature = temperature.number() ?: fieldError("Enter a temperature."),
                windSpeed = windSpeed.ifBlank { "0" }.number() ?: fieldError("Enter a wind speed."),
                windFrom = windFrom.ifBlank { "0" }.number() ?: fieldError("Enter the wind direction in degrees."),
                bearing =
                    targetBearing.takeIf { it.isNotBlank() }?.let {
                        it.number() ?: fieldError("Enter the target direction in degrees.")
                    },
            )
        }

    /** The typed numbers, in the display units. */
    private data class Typed(
        val altitude: Double,
        val temperature: Double,
        val windSpeed: Double,
        val windFrom: Double,
        val bearing: Double?,
    ) {
        fun inMetric(units: UnitSystem): Metric {
            val metric = units == UnitSystem.METRIC
            return Metric(
                altitudeMeters = if (metric) altitude else altitude * METERS_PER_FOOT,
                temperatureC = if (metric) temperature else (temperature - F_OFFSET) / F_PER_C,
                windMps = if (metric) windSpeed / KMH_PER_MPS else windSpeed * MPS_PER_MPH,
                windFromDegrees = windFrom,
                bearingDegrees = bearing,
            )
        }
    }

    private data class Metric(
        val altitudeMeters: Double,
        val temperatureC: Double,
        val windMps: Double,
        val windFromDegrees: Double,
        val bearingDegrees: Double?,
    )

    /** Why [values] can't be conditions, or `null` when they're all in range. */
    private fun rangeError(values: Metric): String? =
        when {
            values.altitudeMeters !in MIN_ALTITUDE_M..MAX_ALTITUDE_M -> "Altitude must be between -500 m and 5,000 m."
            values.temperatureC !in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C -> TEMPERATURE_RANGE
            values.windMps !in 0.0..MAX_WIND_MPS -> "Wind speed must be between 0 and 40 m/s."
            values.windFromDegrees !in 0.0..FULL_TURN -> "Wind direction must be 0–360°."
            values.bearingDegrees?.let { it !in 0.0..FULL_TURN } == true -> "Target direction must be 0–360°."
            else -> null
        }

    companion object {
        /** The editor pre-filled with [conditions] and [targetBearing], rounded for typing. */
        fun of(
            conditions: Conditions,
            targetBearing: TargetBearing?,
            units: UnitSystem,
        ): ConditionsForm =
            ConditionsForm(
                altitude = plain(altitudeDisplay(conditions.altitudeMeters, units)),
                temperature = plain(temperatureDisplay(conditions.temperatureC, units)),
                windSpeed = plain(windDisplay(conditions.wind.speedMps, units)),
                windFrom = plain(conditions.wind.fromDegrees),
                surface = conditions.surface,
                targetBearing = targetBearing?.let { plain(it.normalizedDegrees) }.orEmpty(),
            )

        fun altitudeUnit(units: UnitSystem): String = if (units == UnitSystem.METRIC) "m" else "ft"

        fun temperatureUnit(units: UnitSystem): String = if (units == UnitSystem.METRIC) "°C" else "°F"

        fun windUnit(units: UnitSystem): String = if (units == UnitSystem.METRIC) "km/h" else "mph"

        internal fun altitudeDisplay(
            meters: Double,
            units: UnitSystem,
        ): Double = if (units == UnitSystem.METRIC) meters else meters / METERS_PER_FOOT

        internal fun temperatureDisplay(
            celsius: Double,
            units: UnitSystem,
        ): Double = if (units == UnitSystem.METRIC) celsius else celsius * F_PER_C + F_OFFSET

        internal fun windDisplay(
            mps: Double,
            units: UnitSystem,
        ): Double = if (units == UnitSystem.METRIC) mps * KMH_PER_MPS else mps / MPS_PER_MPH

        /** A whole number, no grouping, "-0" shown as "0": what a user would type. */
        private fun plain(value: Double): String {
            val rounded = ShotMetricFormatter.number(value, 0).replace(",", "")
            return if (rounded == "-0" || abs(value) < HALF) "0" else rounded
        }

        private const val HALF = 0.5
    }
}

/** What [ConditionsForm.parse] returns. */
sealed interface ConditionsFormResult {
    data class Valid(
        val conditions: Conditions,
        val targetBearing: TargetBearing?,
    ) : ConditionsFormResult

    data class Invalid(
        val message: String,
    ) : ConditionsFormResult
}

private fun fieldError(message: String): Nothing = throw IllegalArgumentException(message)

private const val CHECK_VALUES = "Check the values."
private const val TEMPERATURE_RANGE = "Temperature must be between -30 °C and 50 °C."

private fun String.number(): Double? = trim().toDoubleOrNull()?.takeIf { it.isFinite() }

private const val METERS_PER_FOOT = 0.3048
private const val F_PER_C = 1.8
private const val F_OFFSET = 32.0
private const val KMH_PER_MPS = 3.6
private const val MPS_PER_MPH = 0.44704
private const val FULL_TURN = 360.0
private const val MIN_ALTITUDE_M = -500.0
private const val MAX_ALTITUDE_M = 5_000.0
private const val MIN_TEMPERATURE_C = -30.0
private const val MAX_TEMPERATURE_C = 50.0
private const val MAX_WIND_MPS = 40.0
