// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/** How firm the landing area is; it drives the estimated roll after carry. */
enum class Firmness {
    SOFT,
    NORMAL,
    FIRM,
}

/** Where a [Conditions] value came from. */
enum class ConditionsSource {
    /** Typed in by the user. */
    MANUAL,

    /** The phone's own sensors (for example its location's elevation). */
    DEVICE,

    /** A weather service. */
    WEATHER,
}

/**
 * The wind as weather services report it: [speedMps] metres per second, blowing **from**
 * [fromDegrees] (meteorological convention: clockwise from true north, so 270 is a westerly).
 */
data class Wind(
    val speedMps: Double,
    val fromDegrees: Double,
) {
    val isCalm: Boolean get() = speedMps <= 0.0

    companion object {
        val CALM = Wind(speedMps = 0.0, fromDegrees = 0.0)
    }
}

/**
 * The direction the golfer hits toward, clockwise from true north in degrees. Wind can only be
 * turned into head/tail/cross components once this is known; without it, adjustments use air
 * density only (plan F2 §0.2: never assume 0°).
 */
data class TargetBearing(
    val degrees: Double,
) {
    /** [degrees] folded into `[0, 360)`. */
    val normalizedDegrees: Double
        get() = ((degrees % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES

    private companion object {
        const val FULL_TURN_DEGREES = 360.0
    }
}

/**
 * The playing conditions a shot's carry is adjusted for. Pure data: `core:flight` turns it into
 * an air density and a wind vector.
 *
 * @property altitudeMeters elevation above mean sea level.
 * @property temperatureC air temperature.
 * @property humidityPct relative humidity 0–100, or `null` when unknown (treated as dry air).
 * @property pressureHpa station (not sea-level-reduced) pressure, or `null` to derive it from
 *   [altitudeMeters] with the ISA barometric formula.
 * @property surface landing-area firmness for the roll estimate.
 * @property observedAtEpochMillis when these values were observed, `null` for manual entries.
 */
data class Conditions(
    val altitudeMeters: Double,
    val temperatureC: Double,
    val humidityPct: Double? = null,
    val pressureHpa: Double? = null,
    val wind: Wind = Wind.CALM,
    val surface: Firmness = Firmness.NORMAL,
    val source: ConditionsSource = ConditionsSource.MANUAL,
    val observedAtEpochMillis: Long? = null,
) {
    companion object {
        /** ISA sea level: 0 m, 15 °C, dry, calm, normal turf. The server's carry assumes this air. */
        val ISA =
            Conditions(
                altitudeMeters = 0.0,
                temperatureC = 15.0,
                humidityPct = null,
                pressureHpa = null,
                wind = Wind.CALM,
                surface = Firmness.NORMAL,
                source = ConditionsSource.MANUAL,
                observedAtEpochMillis = null,
            )
    }
}
