// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

/**
 * A point on the range canvas, in pixels (x right, y down). The platform-neutral stand-in for
 * Compose's `Offset`, so the projection math stays in commonMain without a Compose dependency.
 * [Unspecified] (both coordinates NaN) marks a point that can't be drawn, like `Offset.Unspecified`.
 */
data class ScreenPoint(
    val x: Float,
    val y: Float,
) {
    /** False for [Unspecified]: the point is behind the camera's near plane. */
    val isSpecified: Boolean
        get() = !x.isNaN() && !y.isNaN()

    companion object {
        val Unspecified: ScreenPoint = ScreenPoint(Float.NaN, Float.NaN)
    }
}
