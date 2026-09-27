// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * What sits in the sky at infinity (plan F8a2a): the [RangeVisualStyle.sun] and the far
 * [ridges]. Both are directions, not places: they turn with the camera's orbit and yaw but never
 * move as the camera travels down the range, and nothing about them animates.
 *
 * [project] runs with the scene's (once per pose), rewrites the ridges' paths in place and the
 * sun's position, and allocates nothing. The platform draws, after the sky gradient and before the
 * distant ground band: the sun's glow (a radial gradient of [RangeSun.glow] over [sunGlowRadius])
 * and disc ([sunDiscRadius]) at ([sunX], [sunY]) while [sunVisible], then each visible ridge in
 * order (far to near), filled with its colour.
 */
class RangeSky<P : PathSink>(
    style: RangeVisualStyle,
    newPath: () -> P,
) {
    /** One silhouette: its path, its colour and its height (as a tangent) at every sample direction. */
    class Ridge<P : PathSink>(
        val path: P,
        val color: RangeColor,
        internal val elevationTangents: DoubleArray,
    ) {
        /** False when none of it is in view; [path] is empty then. */
        var visible = false
            internal set
    }

    val ridges: List<Ridge<P>> =
        style.ridges.map { ridge ->
            Ridge(newPath(), ridge.color, DoubleArray(RIDGE_SAMPLES) { tan(ridgeDegrees(ridge, it) * RADIANS) })
        }

    private val sunDirectionX = sin(style.sun.azimuthDegrees * RADIANS)
    private val sunDirectionZ = -cos(style.sun.azimuthDegrees * RADIANS)
    private val sunElevationTangent = tan(style.sun.elevationDegrees * RADIANS)
    private val sunDiscTangent = tan(style.sun.discRadiusDegrees * RADIANS)
    private val sunGlowTangent = tan(style.sun.glowRadiusDegrees * RADIANS)

    var sunVisible = false
        private set
    var sunX = 0f
        private set
    var sunY = 0f
        private set
    var sunDiscRadius = 0f
        private set
    var sunGlowRadius = 0f
        private set

    private val inView = BooleanArray(RIDGE_SAMPLES)
    private var arcStart = 0
    private var arcLength = 0
    private val point = FloatArray(2)

    /** Re-projects the sun and the ridges for [projection]'s camera, with the horizon at [horizonY]. */
    fun project(
        projection: RangeProjection,
        horizonY: Float,
    ) {
        sunVisible =
            projection.projectDirectionInto(
                sunDirectionX,
                sunDirectionZ,
                sunElevationTangent,
                point,
                0,
                MIN_SUN_COSINE,
            )
        if (sunVisible) {
            sunX = point[0]
            sunY = point[1]
            sunDiscRadius = (projection.focalLengthPixels * sunDiscTangent).toFloat()
            sunGlowRadius = (projection.focalLengthPixels * sunGlowTangent).toFloat()
        }
        findArc(projection)
        for (index in ridges.indices) projectRidge(ridges[index], projection, horizonY)
    }

    /**
     * Marks the sample directions in view and finds the one arc of the circle they form:
     * [arcLength] samples from [arcStart]. Shared by every ridge, since it doesn't depend on height.
     */
    private fun findArc(projection: RangeProjection) {
        arcLength = 0
        for (sample in 0 until RIDGE_SAMPLES) {
            inView[sample] =
                projection.projectDirectionInto(SAMPLE_X[sample], SAMPLE_Z[sample], 0.0, point, 0, MIN_RIDGE_COSINE)
            if (inView[sample]) arcLength++
        }
        arcStart = 0
        for (sample in 0 until RIDGE_SAMPLES) {
            if (inView[sample] && !inView[(sample - 1 + RIDGE_SAMPLES) % RIDGE_SAMPLES]) {
                arcStart = sample
                break
            }
        }
    }

    /** The ridge's top edge across the arc in view, closed along the horizon. */
    private fun projectRidge(
        ridge: Ridge<P>,
        projection: RangeProjection,
        horizonY: Float,
    ) {
        ridge.path.rewind()
        ridge.visible = horizonY.isFinite() && arcLength >= 2
        if (!ridge.visible) return
        var firstX = 0f
        var lastX = 0f
        for (offset in 0 until arcLength) {
            val sample = (arcStart + offset) % RIDGE_SAMPLES
            // A little height barely moves a direction's depth, so every sample of the arc projects.
            projection.projectDirectionInto(
                SAMPLE_X[sample],
                SAMPLE_Z[sample],
                ridge.elevationTangents[sample],
                point,
                0,
                MIN_RIDGE_COSINE * HALF,
            )
            if (offset == 0) {
                firstX = point[0]
                ridge.path.moveTo(point[0], point[1])
            } else {
                ridge.path.lineTo(point[0], point[1])
            }
            lastX = point[0]
        }
        val base = horizonY + RIDGE_BASE_OVERLAP_PIXELS
        ridge.path.lineTo(lastX, base)
        ridge.path.lineTo(firstX, base)
        ridge.path.close()
    }

    companion object {
        /** Sample directions around the horizon: every ⅓°, five or more across each bump of a tree canopy. */
        const val RIDGE_SAMPLES = 1080

        private const val RADIANS = PI / 180
        private const val FULL_TURN = 2 * PI

        /** Directions within about 80° of the view axis; wider than any view's half field of view. */
        private const val MIN_RIDGE_COSINE = 0.17
        private const val MIN_SUN_COSINE = 0.05

        /** The ridges reach a little below the horizon, under the distant ground band, so no sky shows between. */
        private const val RIDGE_BASE_OVERLAP_PIXELS = 2f

        private val SAMPLE_X = DoubleArray(RIDGE_SAMPLES) { sin(FULL_TURN * it / RIDGE_SAMPLES) }
        private val SAMPLE_Z = DoubleArray(RIDGE_SAMPLES) { -cos(FULL_TURN * it / RIDGE_SAMPLES) }

        // The silhouette's shape: smooth hills from three low harmonics, and a canopy of bumps from
        // two high ones. Whole cycles per turn, so the skyline closes on itself all the way round.
        private const val HILL_1_CYCLES = 2
        private const val HILL_2_CYCLES = 5
        private const val HILL_3_CYCLES = 11
        private const val HILL_1_WEIGHT = 0.55
        private const val HILL_2_WEIGHT = 0.30
        private const val HILL_3_WEIGHT = 0.15
        private const val CANOPY_1_CYCLES = 97
        private const val CANOPY_2_CYCLES = 151
        private const val CANOPY_1_WEIGHT = 0.6
        private const val CANOPY_2_WEIGHT = 0.4

        /** How much of the height a full canopy ([RangeRidge.canopy] = 1) takes from the hills. */
        private const val CANOPY_SHARE = 0.6
        private const val HALF = 0.5
        private const val CANOPY_1_PHASE = 3
        private const val CANOPY_2_PHASE = 4

        /**
         * The height of [ridge] above the horizon, in degrees, at sample direction [sample]: its
         * base plus its amplitude times a 0..1 shape seeded by [RangeRidge.seed]. Deterministic, so
         * both platforms draw the same skyline.
         */
        fun ridgeDegrees(
            ridge: RangeRidge,
            sample: Int,
        ): Double {
            val angle = FULL_TURN * sample / RIDGE_SAMPLES

            fun phase(index: Int): Double = unitHash(ridge.seed, index) * FULL_TURN
            val hills =
                HILL_1_WEIGHT * sin(HILL_1_CYCLES * angle + phase(0)) +
                    HILL_2_WEIGHT * sin(HILL_2_CYCLES * angle + phase(1)) +
                    HILL_3_WEIGHT * sin(HILL_3_CYCLES * angle + phase(2))
            val canopy =
                CANOPY_1_WEIGHT * abs(sin(CANOPY_1_CYCLES * angle + phase(CANOPY_1_PHASE))) +
                    CANOPY_2_WEIGHT * abs(sin(CANOPY_2_CYCLES * angle + phase(CANOPY_2_PHASE)))
            val share = ridge.canopy * CANOPY_SHARE
            val shape = (hills + 1) * HALF * (1 - share) + canopy * share
            return ridge.baseDegrees + ridge.amplitudeDegrees * shape
        }
    }
}

/**
 * A deterministic hash of ([a], [b]) to 0..1, the same on every platform (32-bit integer
 * arithmetic wraps identically on the JVM and Kotlin/Native): the seed behind every procedural
 * shape of the scene.
 */
@Suppress("MagicNumber") // Hash multipliers and shifts.
internal fun unitHash(
    a: Int,
    b: Int,
): Double {
    var h = a * 374_761_393 + b * 668_265_263
    h = (h xor (h ushr 13)) * 1_274_126_177
    h = h xor (h ushr 16)
    return (h and 0x7FFF_FFFF) / 2_147_483_648.0
}
