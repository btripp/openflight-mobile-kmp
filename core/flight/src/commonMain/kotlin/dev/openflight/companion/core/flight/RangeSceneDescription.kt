// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

/**
 * A single yardage marker on the range. Ported from `RangeSceneDescription.swift`'s
 * `RangeMarkerDescription`.
 */
data class RangeMarkerDescription(
    val yards: Int,
    val radiusMeters: Double,
)

/** A single background tree placement. Ported from `RangeSceneDescription.swift`'s `RangeTreeDescription`. */
data class RangeTreeDescription(
    val xMeters: Double,
    val downrangeMeters: Double,
    val scale: Double,
)

/**
 * The pure, platform-agnostic description of the driving-range scene: yardage markers and tree
 * placements. Ported from `ios/OpenFlight/DrivingRange/RangeSceneDescription.swift`; the
 * `RealityKit`/`SwiftUI` rendering pieces of that file (`ModelEntity`, `Material`, etc.) are
 * inherently rendering-specific and are not ported here.
 */
data class RangeSceneDescription(
    val markers: List<RangeMarkerDescription>,
    val trees: List<RangeTreeDescription>,
    val rangeDepthMeters: Double,
    val fairwayWidthMeters: Double,
) {
    /**
     * Where each of [markers] sits on the ground, in scene space (y up, downrange is −z): they
     * alternate left and right of the target line, starting on the left (RangeSceneController.swift
     * `addTargets`). The renderers draw them here and the follow camera frames the nearest one.
     */
    val markerScenePositions: List<Vec3> =
        markers.mapIndexed { index, marker ->
            val side = if (index % 2 == 0) -1.0 else 1.0
            Vec3(side * MARKER_LATERAL_OFFSET_METERS, 0.0, -marker.yards * YARDS_TO_METERS)
        }

    companion object {
        /** The yardage markers' distance left or right of the target line. */
        const val MARKER_LATERAL_OFFSET_METERS = 9.0
        const val YARDS_TO_METERS = 0.9144
        private const val FIRST_MARKER_YARDS = 50
        private const val LAST_MARKER_YARDS = 350
        private const val MARKER_YARDS_STEP = 50
        private const val HUNDRED_YARD_MARKER_RADIUS_METERS = 7.5
        private const val STANDARD_MARKER_RADIUS_METERS = 5.5
        private const val TREE_SIDE_BASE_OFFSET_METERS = 32.0
        private const val TREE_SIDE_JITTER_STEP_METERS = 5.0
        private const val TREE_SIDE_JITTER_MODULUS = 3
        private const val TREE_LANE_SPACING_METERS = 18.0
        private const val TREE_BASE_DOWNRANGE_METERS = 22.0
        private const val TREE_SCALE_BASE = 0.82
        private const val TREE_SCALE_JITTER_STEP = 0.09
        private const val TREE_SCALE_JITTER_MODULUS = 4
        private const val RANGE_DEPTH_METERS = 390.0
        private const val FAIRWAY_WIDTH_METERS = 52.0
        private const val ONE_HUNDRED_YARDS = 100

        fun standard(treeCount: Int): RangeSceneDescription {
            val markers =
                (FIRST_MARKER_YARDS..LAST_MARKER_YARDS step MARKER_YARDS_STEP).map { yards ->
                    RangeMarkerDescription(
                        yards = yards,
                        radiusMeters =
                            if (yards % ONE_HUNDRED_YARDS == 0) {
                                HUNDRED_YARD_MARKER_RADIUS_METERS
                            } else {
                                STANDARD_MARKER_RADIUS_METERS
                            },
                    )
                }
            val count = maxOf(0, treeCount)
            val trees =
                (0 until count).map { index ->
                    val side = if (index % 2 == 0) -1.0 else 1.0
                    val lane = (index / 2).toDouble()
                    RangeTreeDescription(
                        xMeters =
                            side * (
                                TREE_SIDE_BASE_OFFSET_METERS +
                                    (index % TREE_SIDE_JITTER_MODULUS) * TREE_SIDE_JITTER_STEP_METERS
                            ),
                        downrangeMeters = TREE_BASE_DOWNRANGE_METERS + lane * TREE_LANE_SPACING_METERS,
                        scale = TREE_SCALE_BASE + (index % TREE_SCALE_JITTER_MODULUS) * TREE_SCALE_JITTER_STEP,
                    )
                }
            return RangeSceneDescription(
                markers = markers,
                trees = trees,
                rangeDepthMeters = RANGE_DEPTH_METERS,
                fairwayWidthMeters = FAIRWAY_WIDTH_METERS,
            )
        }
    }
}

/** The continuous flight-path tracer's visual style. Ported from `RangeSceneDescription.swift`. */
data class RangeTracerStyle(
    val nearWidthMeters: Double,
    val farWidthMeters: Double,
    val opacity: Double,
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    companion object {
        val highVisibility =
            RangeTracerStyle(
                nearWidthMeters = 0.080,
                farWidthMeters = 0.52,
                opacity = 0.84,
                red = 0.04,
                green = 0.42,
                blue = 1.0,
            )
    }
}

/**
 * Reusable-resource budget for the range scene, ported from `RangeSceneDescription.swift`'s
 * `RangeQualityProfile`. The reference picks [balanced] vs [high] from
 * `ProcessInfo.processInfo.physicalMemory`; that device inspection is platform-specific and is
 * left to the caller (e.g. an `expect`/`actual` in a later step) rather than ported here.
 */
enum class RangeQualityProfile {
    BALANCED,
    HIGH,
    ;

    val treeCount: Int
        get() =
            when (this) {
                BALANCED -> BALANCED_TREE_COUNT
                HIGH -> HIGH_TREE_COUNT
            }

    val tracerPointCount: Int
        get() =
            when (this) {
                BALANCED -> BALANCED_TRACER_POINT_COUNT
                HIGH -> HIGH_TRACER_POINT_COUNT
            }

    private companion object {
        const val BALANCED_TREE_COUNT = 20
        const val HIGH_TREE_COUNT = 36
        const val BALANCED_TRACER_POINT_COUNT = 72
        const val HIGH_TRACER_POINT_COUNT = 120
    }
}
