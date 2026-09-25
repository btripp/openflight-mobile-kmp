// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing
import kotlin.math.abs

/** Which server carry a [ShotDistanceEstimate] is anchored to. */
enum class CarryAnchor {
    /** `carry_spin_adjusted`: the server's ballistic carry at ISA air. Preferred when present. */
    SPIN_ADJUSTED,

    /** `estimated_carry_yards`: the server's table carry. */
    TABLE,
}

/**
 * Carry, roll and total for one shot under some [Conditions] (plan F2 §0.2).
 *
 * @property carryYards the anchor carry, adjusted for conditions when [isAdjusted].
 * @property rollYards the estimated run after carry (always [DistanceProvenance.ESTIMATED]).
 * @property totalYards [carryYards] + [rollYards].
 * @property lateralDriftYards extra sideways movement from the wind (+ right); 0 without wind.
 * @property anchorCarryYards the server carry the adjustment started from.
 * @property anchor which server carry that was.
 * @property isAdjusted whether density or wind changed the carry (false at ISA and calm).
 * @property windApplied whether wind was included.
 * @property windNeedsTargetBearing true when there's wind but no target bearing, so it was left
 *   out; the UI shows "Set your target direction to include wind".
 * @property airDensity the conditions' density in kg/m³.
 * @property flightProvenance which launch inputs were estimated or clamped by [FlightInputResolver].
 */
data class ShotDistanceEstimate(
    val carryYards: Double,
    val rollYards: Double,
    val totalYards: Double,
    val lateralDriftYards: Double,
    val anchorCarryYards: Double,
    val anchor: CarryAnchor,
    val isAdjusted: Boolean,
    val windApplied: Boolean,
    val windNeedsTargetBearing: Boolean,
    val airDensity: Double,
    val flightProvenance: FlightInputProvenance,
) {
    /** [DistanceProvenance.SERVER] when the carry is the server's own number. */
    val carryProvenance: DistanceProvenance
        get() = if (isAdjusted) DistanceProvenance.ESTIMATED else DistanceProvenance.SERVER

    val rollProvenance: DistanceProvenance get() = DistanceProvenance.ESTIMATED

    val totalProvenance: DistanceProvenance get() = DistanceProvenance.ESTIMATED

    /** The spin behind the roll (and the adjustment) came from the per-club defaults, not the radar. */
    val spinEstimated: Boolean
        get() = FlightParameter.SPIN_RATE in flightProvenance.estimatedParameters
}

/**
 * The one entry point for conditions-adjusted carry, roll and total. It keys on
 * [FlightMeasurements] so live shots ([dev.openflight.companion.core.model.ShotEvent]) and
 * history rows ([dev.openflight.companion.core.model.pi.ShotDetail]) both work.
 *
 * Missing launch or spin is filled by [FlightInputResolver] and flagged in
 * [ShotDistanceEstimate.flightProvenance]. Two simulator runs per shot (one at ISA when the
 * conditions are ISA and calm).
 */
class ShotDistanceEstimator(
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val adjuster: ConditionsAdjuster = ConditionsAdjuster(),
    private val rollEstimator: RollEstimator = RollEstimator(),
) {
    /** The estimate for [shot], or `null` when it has no usable ball speed or carry. */
    fun estimate(
        shot: FlightMeasurements,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): ShotDistanceEstimate? {
        val input =
            try {
                resolver.resolve(shot)
            } catch (_: FlightInputResolutionError) {
                return null
            }
        val spinAdjusted = shot.carrySpinAdjustedYards?.takeIf { it.isFinite() && it > 0 }
        val anchor = if (spinAdjusted != null) CarryAnchor.SPIN_ADJUSTED else CarryAnchor.TABLE
        val anchorCarry = spinAdjusted ?: shot.carryYards

        val adjusted = adjuster.adjust(input, anchorCarry, conditions, targetBearing)
        val densityChanged = abs(adjusted.airDensity - AirDensity.ISA_SEA_LEVEL) > ISA_DENSITY_TOLERANCE
        val isAdjusted = densityChanged || adjusted.windApplied
        val carry = if (isAdjusted) adjusted.carryYards else anchorCarry
        val roll = rollEstimator.estimate(adjusted.landing, adjusted.landingSpinRpm, conditions.surface).rollYards

        return ShotDistanceEstimate(
            carryYards = carry,
            rollYards = roll,
            totalYards = carry + roll,
            lateralDriftYards = if (adjusted.windApplied) adjusted.lateralDriftYards else 0.0,
            anchorCarryYards = anchorCarry,
            anchor = anchor,
            isAdjusted = isAdjusted,
            windApplied = adjusted.windApplied,
            windNeedsTargetBearing = targetBearing == null && !conditions.wind.isCalm,
            airDensity = adjusted.airDensity,
            flightProvenance = input.provenance,
        )
    }

    private companion object {
        /**
         * Densities within this of 1.225 count as ISA: [AirDensity] gives 1.22501 for ISA itself,
         * and 0.0005 kg/m³ moves a drive's carry by under 0.1 yd.
         */
        const val ISA_DENSITY_TOLERANCE = 0.0005
    }
}
