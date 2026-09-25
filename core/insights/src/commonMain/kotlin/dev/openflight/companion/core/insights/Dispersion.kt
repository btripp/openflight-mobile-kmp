// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where one shot landed, seen from above: [offlineYards] is positive to the right of the target
 * line, [carryYards] is downrange from the tee.
 */
data class DispersionSample(
    val offlineYards: Double,
    val carryYards: Double,
)

/**
 * A club's typical landing area: the ellipse holding about [DISPERSION_ELLIPSE_COVERAGE] of its
 * shots, assuming they scatter normally. [rotationDegrees] is the angle of the major axis,
 * counter-clockwise from the +offline axis towards +carry (so a screen with carry pointing up
 * rotates by `-rotationDegrees`).
 */
data class DispersionEllipse(
    val centerOfflineYards: Double,
    val centerCarryYards: Double,
    val semiMajorYards: Double,
    val semiMinorYards: Double,
    val rotationDegrees: Double,
) {
    /** Half the width of the ellipse's axis-aligned bounding box. */
    val halfExtentOfflineYards: Double
        get() = hypot(semiMajorYards * cos(radians), semiMinorYards * sin(radians))

    /** Half the height of the ellipse's axis-aligned bounding box. */
    val halfExtentCarryYards: Double
        get() = hypot(semiMajorYards * sin(radians), semiMinorYards * cos(radians))

    private val radians: Double get() = rotationDegrees * PI / DEGREES_PER_HALF_TURN
}

/** Share of shots the ellipse is sized to hold. */
const val DISPERSION_ELLIPSE_COVERAGE: Double = 0.68

/** `sqrt(chi2.ppf(0.68, df = 2))`: scales a 1σ ellipse to hold 68% of a 2D normal scatter. */
private const val ELLIPSE_SCALE = 1.5096

/** Fewer shots than this don't say much about a club's spread, so they get no ellipse. */
const val MIN_SHOTS_FOR_ELLIPSE: Int = 3

private const val DEGREES_PER_HALF_TURN = 180.0

/**
 * The [DispersionEllipse] for [samples], from their mean and sample covariance. `null` below
 * [MIN_SHOTS_FOR_ELLIPSE] samples.
 */
fun computeDispersionEllipse(samples: List<DispersionSample>): DispersionEllipse? {
    if (samples.size < MIN_SHOTS_FOR_ELLIPSE) return null

    val meanX = samples.sumOf { it.offlineYards } / samples.size
    val meanY = samples.sumOf { it.carryYards } / samples.size
    val degreesOfFreedom = samples.size - 1
    val varX = samples.sumOf { (it.offlineYards - meanX).squared() } / degreesOfFreedom
    val varY = samples.sumOf { (it.carryYards - meanY).squared() } / degreesOfFreedom
    val cov = samples.sumOf { (it.offlineYards - meanX) * (it.carryYards - meanY) } / degreesOfFreedom

    // Eigenvalues of the symmetric 2x2 covariance matrix [[varX, cov], [cov, varY]].
    val halfTrace = (varX + varY) / 2
    val radius = hypot((varX - varY) / 2, cov)
    val major = halfTrace + radius
    val minor = max(halfTrace - radius, 0.0)
    val rotation = atan2(2 * cov, varX - varY) / 2

    return DispersionEllipse(
        centerOfflineYards = meanX,
        centerCarryYards = meanY,
        semiMajorYards = ELLIPSE_SCALE * sqrt(major),
        semiMinorYards = ELLIPSE_SCALE * sqrt(minor),
        rotationDegrees = rotation * DEGREES_PER_HALF_TURN / PI,
    )
}

/** A club needs at least this many shots before any of them is called a likely bad read. */
const val MIN_SHOTS_FOR_OUTLIERS: Int = 5

/** Spread assumed at least this wide (robust 1σ, yards), so a tight group doesn't flag normal shots. */
private const val MIN_OUTLIER_SIGMA_YARDS = 3.0

/** Scales a median absolute deviation to a standard deviation for normally scattered shots. */
private const val MAD_TO_SIGMA = 1.4826

/** Iglewicz and Hoaglin's cut-off for a robust (median/MAD) z-score. */
private const val OUTLIER_ROBUST_Z = 3.5

/**
 * Indexes of the [samples] (one club's shots) that land far from the rest: a robust z-score
 * (distance from the median, over the median absolute deviation) above 3.5 in carry, or in
 * offline for shots whose side was measured ([sideMeasured], same order as [samples]). Medians
 * aren't pulled by the bad reads themselves, and stay sensible for the handful of shots a club
 * has in a session, unlike a mean/covariance test. Empty below [MIN_SHOTS_FOR_OUTLIERS] shots.
 */
fun findDispersionOutliers(
    samples: List<DispersionSample>,
    sideMeasured: List<Boolean>,
): Set<Int> {
    require(samples.size == sideMeasured.size) { "samples and sideMeasured differ in size" }
    if (samples.size < MIN_SHOTS_FOR_OUTLIERS) return emptySet()
    val carryFar = robustOutliers(samples.map { it.carryYards })
    val measured = samples.indices.filter { sideMeasured[it] }
    val offlineFar =
        if (measured.size >= MIN_SHOTS_FOR_OUTLIERS) {
            robustOutliers(measured.map { samples[it].offlineYards }).map { measured[it] }.toSet()
        } else {
            emptySet()
        }
    return carryFar + offlineFar
}

/** Indexes of [values] whose robust z-score exceeds [OUTLIER_ROBUST_Z]. */
private fun robustOutliers(values: List<Double>): Set<Int> {
    val median = values.median()
    val sigma = max(values.map { abs(it - median) }.median() * MAD_TO_SIGMA, MIN_OUTLIER_SIGMA_YARDS)
    return values.indices.filter { abs(values[it] - median) / sigma > OUTLIER_ROBUST_Z }.toSet()
}

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/**
 * The part of the range a dispersion chart shows, in yards: carry from [minCarryYards] to
 * [maxCarryYards], offline from `-halfWidthYards` to `+halfWidthYards`, with distance [arcs]
 * (circles around the tee). Shared so both platforms frame the same shots the same way.
 */
data class DispersionViewport(
    val minCarryYards: Double,
    val maxCarryYards: Double,
    val halfWidthYards: Double,
    val arcs: List<DispersionArc>,
)

/**
 * A distance arc: a circle of [radiusYards] around the tee, labelled [label] in the display unit
 * (so metric arcs sit at round metres, not at relabelled yards).
 */
data class DispersionArc(
    val radiusYards: Double,
    val label: Int,
)

private const val VIEWPORT_PADDING_YARDS = 10.0
private const val VIEWPORT_ROUNDING_YARDS = 10.0
private const val MIN_HALF_WIDTH_YARDS = 20.0
private const val HALF_WIDTH_ROUNDING_YARDS = 5.0
private const val WIDE_ARC_STEP_YARDS = 50
private const val NARROW_ARC_STEP_YARDS = 25
private const val NARROW_SPAN_YARDS = 60.0

/**
 * A viewport that holds every sample and ellipse with some padding, rounded to tidy yardages.
 * Arcs are every 50 display units (25 over a short span); [unitsPerYard] is 1 for yards and
 * `0.9144` (metres per yard) for metres. `null` when there's nothing to show.
 */
fun computeDispersionViewport(
    samples: List<DispersionSample>,
    ellipses: List<DispersionEllipse> = emptyList(),
    unitsPerYard: Double = 1.0,
): DispersionViewport? {
    if (samples.isEmpty()) return null

    var lowCarry = samples.minOf { it.carryYards }
    var highCarry = samples.maxOf { it.carryYards }
    var widestOffline = samples.maxOf { abs(it.offlineYards) }
    for (ellipse in ellipses) {
        lowCarry = min(lowCarry, ellipse.centerCarryYards - ellipse.halfExtentCarryYards)
        highCarry = max(highCarry, ellipse.centerCarryYards + ellipse.halfExtentCarryYards)
        widestOffline = max(widestOffline, abs(ellipse.centerOfflineYards) + ellipse.halfExtentOfflineYards)
    }

    val minCarry =
        max(
            0.0,
            floor((lowCarry - VIEWPORT_PADDING_YARDS) / VIEWPORT_ROUNDING_YARDS) * VIEWPORT_ROUNDING_YARDS,
        )
    val maxCarry = ceil((highCarry + VIEWPORT_PADDING_YARDS) / VIEWPORT_ROUNDING_YARDS) * VIEWPORT_ROUNDING_YARDS
    val halfWidth =
        max(
            MIN_HALF_WIDTH_YARDS,
            ceil((widestOffline + VIEWPORT_PADDING_YARDS) / HALF_WIDTH_ROUNDING_YARDS) * HALF_WIDTH_ROUNDING_YARDS,
        )
    val minUnits = minCarry * unitsPerYard
    val maxUnits = maxCarry * unitsPerYard
    val step = if (maxUnits - minUnits < NARROW_SPAN_YARDS) NARROW_ARC_STEP_YARDS else WIDE_ARC_STEP_YARDS
    val firstArc = ceil(minUnits / step).toInt() * step
    val arcs =
        (firstArc..floor(maxUnits).toInt() step step)
            .filter { it > 0 }
            .map { DispersionArc(radiusYards = it / unitsPerYard, label = it) }

    return DispersionViewport(minCarry, maxCarry, halfWidth, arcs)
}

/**
 * Maps [viewport] into a [width] × [height] canvas (any unit, y down). Carry fills the height;
 * offline gets at least the same scale, stretched up to [maxStretch] times so a club's side-to-side
 * spread stays readable next to a long carry range (like a launch monitor's dispersion view). Arcs
 * and ellipses go through the same mapping ([arcRadii], [ellipseOutline]), so they stay true to the
 * data. The target line is centred, and so is the carry range.
 */
class DispersionProjection(
    viewport: DispersionViewport,
    val width: Double,
    val height: Double,
    maxStretch: Double = DEFAULT_MAX_STRETCH,
) {
    /** Canvas units per yard of offline distance. */
    val xScale: Double

    /** Canvas units per yard of carry. */
    val yScale: Double

    init {
        val fitWidth = width / (2 * viewport.halfWidthYards)
        val fitHeight = height / (viewport.maxCarryYards - viewport.minCarryYards)
        yScale = min(fitWidth, fitHeight)
        xScale = min(fitWidth, yScale * maxStretch)
    }

    private val centerCarryYards = (viewport.minCarryYards + viewport.maxCarryYards) / 2

    /** Canvas x of [offlineYards]. */
    fun x(offlineYards: Double): Double = width / 2 + offlineYards * xScale

    /** Canvas y of [carryYards]; carry grows upwards. */
    fun y(carryYards: Double): Double = height / 2 - (carryYards - centerCarryYards) * yScale

    /** The tee, the centre of every distance arc (usually below the canvas). */
    val teeX: Double get() = x(0.0)

    /** The tee, the centre of every distance arc (usually below the canvas). */
    val teeY: Double get() = y(0.0)

    /** Horizontal and vertical canvas radii of [arc]: an axis-aligned oval around the tee. */
    fun arcRadii(arc: DispersionArc): Pair<Double, Double> = arc.radiusYards * xScale to arc.radiusYards * yScale

    /**
     * Canvas y where [arc] crosses canvas x [atX], on its far (upper) side; `null` when the arc
     * doesn't reach that far sideways.
     */
    fun arcY(
        arc: DispersionArc,
        atX: Double,
    ): Double? {
        val (radiusX, radiusY) = arcRadii(arc)
        val t = (atX - teeX) / radiusX
        if (abs(t) >= 1) return null
        return teeY - radiusY * sqrt(1 - t * t)
    }

    /** [ellipse]'s outline as a closed canvas polygon of [segments] points. */
    fun ellipseOutline(
        ellipse: DispersionEllipse,
        segments: Int = ELLIPSE_SEGMENTS,
    ): List<Pair<Double, Double>> {
        val rotation = ellipse.rotationDegrees * PI / DEGREES_PER_HALF_TURN
        return List(segments) { index ->
            val angle = 2 * PI * index / segments
            val alongMajor = ellipse.semiMajorYards * cos(angle)
            val alongMinor = ellipse.semiMinorYards * sin(angle)
            val offline = ellipse.centerOfflineYards + alongMajor * cos(rotation) - alongMinor * sin(rotation)
            val carry = ellipse.centerCarryYards + alongMajor * sin(rotation) + alongMinor * cos(rotation)
            x(offline) to y(carry)
        }
    }

    /**
     * Index of the sample drawn closest to ([tapX], [tapY]), if it's within [radius] canvas units.
     */
    fun nearest(
        samples: List<DispersionSample>,
        tapX: Double,
        tapY: Double,
        radius: Double,
    ): Int? {
        var best: Int? = null
        var bestDistance = radius
        samples.forEachIndexed { index, sample ->
            val distance = hypot(x(sample.offlineYards) - tapX, y(sample.carryYards) - tapY)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    companion object {
        /** How much wider than carry the offline axis may be drawn. */
        const val DEFAULT_MAX_STRETCH: Double = 3.0
        private const val ELLIPSE_SEGMENTS = 64
    }
}

private fun Double.squared(): Double = this * this
