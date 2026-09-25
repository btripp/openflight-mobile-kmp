// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.Conditions
import kotlin.math.pow

/**
 * Air density from [Conditions] (plan F2 §0.2).
 *
 * - Pressure: the station pressure when known, otherwise the ICAO/ISA troposphere barometric
 *   formula `p = 101325·(1 − 2.25577e-5·h)^5.25588` Pa (ICAO Doc 7488, Manual of the ICAO
 *   Standard Atmosphere).
 * - Dry air: `ρ = p / (R_d·T)` with `R_d = 287.05 J/(kg·K)`.
 * - Humid air, when humidity is known: the partial pressures of dry air and water vapour,
 *   `ρ = p_d/(R_d·T) + p_v/(R_v·T)`, with `R_v = 461.495 J/(kg·K)` and the saturation vapour
 *   pressure from the Tetens formula `e_s = 6.1078·10^(7.5·T/(T + 237.3))` hPa (Tetens 1930, as
 *   given by Murray 1967, J. Appl. Meteor. 6:203). Water vapour is lighter than dry air, so humid
 *   air is slightly less dense.
 *
 * Checks (dry): ISA sea level at 15 °C gives 1.225; 1609 m at 15 °C gives about 834 hPa and 1.009;
 * 1609 m at the ISA temperature (4.5 °C) gives 1.047.
 */
object AirDensity {
    /**
     * The ISA sea-level density the server's carry assumes (`ballistics.py` `AIR_DENSITY_STD`).
     * The range renderer uses 1.204 instead (the reference iOS app's warmer air); conditions
     * adjustments are always relative to this value.
     */
    const val ISA_SEA_LEVEL = 1.225

    private const val SEA_LEVEL_PRESSURE_PA = 101_325.0
    private const val BAROMETRIC_LAPSE_FACTOR = 2.25577e-5
    private const val BAROMETRIC_EXPONENT = 5.25588
    private const val DRY_AIR_GAS_CONSTANT = 287.05
    private const val WATER_VAPOUR_GAS_CONSTANT = 461.495
    private const val KELVIN_OFFSET = 273.15
    private const val PASCALS_PER_HECTOPASCAL = 100.0
    private const val TETENS_BASE_HPA = 6.1078
    private const val TETENS_A = 7.5
    private const val TETENS_B = 237.3
    private const val PERCENT = 100.0
    private const val TEN = 10.0

    /** Density in kg/m³ for [conditions]. */
    fun of(conditions: Conditions): Double =
        of(
            altitudeMeters = conditions.altitudeMeters,
            temperatureC = conditions.temperatureC,
            humidityPct = conditions.humidityPct,
            pressureHpa = conditions.pressureHpa,
        )

    /** Density in kg/m³; see the class KDoc for the formulas. */
    fun of(
        altitudeMeters: Double,
        temperatureC: Double,
        humidityPct: Double? = null,
        pressureHpa: Double? = null,
    ): Double {
        val pressurePa = pressureHpa?.let { it * PASCALS_PER_HECTOPASCAL } ?: pressureAtAltitudePa(altitudeMeters)
        val kelvin = temperatureC + KELVIN_OFFSET
        val relativeHumidity = (humidityPct ?: 0.0).coerceIn(0.0, PERCENT) / PERCENT
        val vapourPa = relativeHumidity * saturationVapourPressureHpa(temperatureC) * PASCALS_PER_HECTOPASCAL
        val dryPa = pressurePa - vapourPa
        return dryPa / (DRY_AIR_GAS_CONSTANT * kelvin) + vapourPa / (WATER_VAPOUR_GAS_CONSTANT * kelvin)
    }

    /** ISA troposphere pressure at [altitudeMeters], in pascals. */
    fun pressureAtAltitudePa(altitudeMeters: Double): Double =
        SEA_LEVEL_PRESSURE_PA * (1.0 - BAROMETRIC_LAPSE_FACTOR * altitudeMeters).pow(BAROMETRIC_EXPONENT)

    private fun saturationVapourPressureHpa(temperatureC: Double): Double =
        TETENS_BASE_HPA * TEN.pow(TETENS_A * temperatureC / (temperatureC + TETENS_B))
}
