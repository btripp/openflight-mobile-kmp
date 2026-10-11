// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.flight.Curve
import dev.openflight.companion.core.flight.ShotShape

/** How many of a club's shots curved one way, e.g. 6 fades. */
data class ShapeTally(
    val curve: Curve,
    val label: String,
    val count: Int,
)

/**
 * A set of shots' typical shape, for the bag ("Mostly fade").
 *
 * @property headline "Mostly fade", "Leans left" or "Mixed"; with fewer than [MIN_SHOTS_FOR_TREND]
 *   measured shots, the most common shape alone ("Fade").
 * @property tallies one per curve that happened, hook to slice (left to right).
 * @property measuredCount shots with a measured spin axis; the rest can't be shaped.
 */
data class ShotShapeSummary(
    val headline: String,
    val tallies: List<ShapeTally>,
    val measuredCount: Int,
) {
    /** "Draw 2 · Straight 3 · Fade 6". */
    val talliesLabel: String get() = tallies.joinToString(" · ") { "${it.label} ${it.count}" }

    companion object {
        const val MIN_SHOTS_FOR_TREND = 3
    }
}

/**
 * The typical curve of [shapes]: one curve on at least half the shots is "Mostly <curve>"; else one
 * side (draw and hook, or fade and slice) on at least 60 % "Leans left/right"; else "Mixed". Shots
 * whose curve wasn't measured are left out; `null` when none was.
 */
fun summarizeShapes(shapes: List<ShotShape>): ShotShapeSummary? {
    val curves = shapes.mapNotNull { it.curve }
    if (curves.isEmpty()) return null
    val counts = curves.groupingBy { it }.eachCount()
    val tallies = Curve.entries.mapNotNull { curve -> counts[curve]?.let { ShapeTally(curve, curve.label, it) } }
    val top = tallies.maxBy { it.count }
    val total = curves.size
    val left = curves.count { it == Curve.DRAW || it == Curve.HOOK }
    val right = curves.count { it == Curve.FADE || it == Curve.SLICE }
    val headline =
        when {
            total < ShotShapeSummary.MIN_SHOTS_FOR_TREND -> top.label
            top.count * 2 >= total -> "Mostly ${top.label.lowercase()}"
            left >= total * LEAN_SHARE -> "Leans left"
            right >= total * LEAN_SHARE -> "Leans right"
            else -> "Mixed"
        }
    return ShotShapeSummary(headline, tallies, total)
}

private const val LEAN_SHARE = 0.6

private val Curve.label: String
    get() =
        when (this) {
            Curve.HOOK -> "Hook"
            Curve.DRAW -> "Draw"
            Curve.STRAIGHT -> "Straight"
            Curve.FADE -> "Fade"
            Curve.SLICE -> "Slice"
        }
