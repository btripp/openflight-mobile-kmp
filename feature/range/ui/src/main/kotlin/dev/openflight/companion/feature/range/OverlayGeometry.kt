// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.openflight.companion.core.designsystem.OfClubPalette

/**
 * The overlay's static trajectories (plan F8a1) in scene space, built once per overlay, and their
 * projection: one [Path] per club colour plus one for the selected shot, and each landing's
 * screen point. [reproject] runs only when the camera changes (the overlay has no follow camera),
 * rewrites the same paths and arrays and allocates nothing, so a 200-shot overlay costs a handful
 * of `drawPath` calls per frame.
 */
internal class OverlayGeometry(
    val flights: List<OverlayFlight>,
) {
    private val offsets = IntArray(flights.size + 1)
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val zs: DoubleArray
    private val groupOf: IntArray

    /** One path per distinct club colour, drawn in [groupColors]. */
    val groupPaths: List<Path>
    val groupColors: List<Color>

    /** The selected shot's tracer, drawn over the rest; empty when nothing is selected. */
    val selectedPath = Path()
    private var selected = -1

    val landingXs = FloatArray(flights.size)
    val landingYs = FloatArray(flights.size)
    val landingColors: List<Color>
    private val scratch = FloatArray(2)

    init {
        var total = 0
        flights.forEachIndexed { index, flight ->
            offsets[index] = total
            total += flight.trajectory.points.size
        }
        offsets[flights.size] = total
        xs = DoubleArray(total)
        ys = DoubleArray(total)
        zs = DoubleArray(total)
        flights.forEachIndexed { index, flight ->
            flight.trajectory.points.forEachIndexed { point, sample ->
                val scene = RangeProjection.flightToScene(sample.positionMeters)
                xs[offsets[index] + point] = scene.x
                ys[offsets[index] + point] = scene.y
                zs[offsets[index] + point] = scene.z
            }
        }
        val colorIndices = flights.map { it.colorIndex.mod(OfClubPalette.colors.size) }.distinct().sorted()
        groupColors = colorIndices.map { OfClubPalette.color(it).copy(alpha = TRACER_ALPHA) }
        groupPaths = colorIndices.map { Path() }
        groupOf = IntArray(flights.size) { colorIndices.indexOf(flights[it].colorIndex.mod(OfClubPalette.colors.size)) }
        landingColors = flights.map { OfClubPalette.color(it.colorIndex) }
    }

    /** Highlights [shotId] (or nothing) from the next [reproject]. */
    fun select(shotId: String?): Boolean {
        val index = flights.indexOfFirst { it.shotId == shotId }
        if (index == selected) return false
        selected = index
        return true
    }

    val selectedIndex: Int get() = selected

    fun reproject(projection: RangeProjection) {
        for (path in groupPaths) path.rewind()
        selectedPath.rewind()
        for (flight in flights.indices) {
            trace(projection, flight, groupPaths[groupOf[flight]])
            if (flight == selected) trace(projection, flight, selectedPath)
            val last = offsets[flight + 1] - 1
            if (last >= offsets[flight] && projection.projectInto(xs[last], 0.0, zs[last], scratch, 0)) {
                landingXs[flight] = scratch[0]
                landingYs[flight] = scratch[1]
            } else {
                landingXs[flight] = Float.NaN
                landingYs[flight] = Float.NaN
            }
        }
    }

    /** Adds one flight to [path], lifting the pen where it passes behind the camera. */
    private fun trace(
        projection: RangeProjection,
        flight: Int,
        path: Path,
    ) {
        var penDown = false
        for (point in offsets[flight] until offsets[flight + 1]) {
            if (projection.projectInto(xs[point], ys[point], zs[point], scratch, 0)) {
                if (penDown) path.lineTo(scratch[0], scratch[1]) else path.moveTo(scratch[0], scratch[1])
                penDown = true
            } else {
                penDown = false
            }
        }
    }

    private companion object {
        const val TRACER_ALPHA = 0.7f
    }
}
