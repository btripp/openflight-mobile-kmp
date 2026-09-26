// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

/**
 * The overlay's static trajectories (plan F8a1) in scene space, built once per overlay, and their
 * projection: one [PathSink] per club colour plus one for the selected shot, and each landing's
 * screen point. [reproject] runs only when the camera changes (the overlay has no follow camera),
 * rewrites the same paths and arrays and allocates nothing, so a 200-shot overlay costs a handful
 * of path draws per frame.
 *
 * Colours are indices into the platform's club palette (`OfClubPalette` / `Theme.clubColors`) of
 * [paletteSize] colours: stroke [groupPaths] in palette colour [groupColorIndices] at
 * [RangeVisualStyle.overlayTracerAlpha], each landing dot in [landingColorIndices], and the
 * selection in [RangeVisualStyle.overlaySelected].
 */
class OverlayGeometry<P : PathSink>(
    val flights: List<OverlayFlight>,
    paletteSize: Int,
    newPath: () -> P,
) {
    private val offsets = IntArray(flights.size + 1)
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val zs: DoubleArray
    private val groupOf: IntArray

    /** One path per distinct club colour, drawn in palette colour [groupColorIndices]. */
    val groupPaths: List<P>
    val groupColorIndices: IntArray

    /** The selected shot's tracer, drawn over the rest; empty when nothing is selected. */
    val selectedPath: P = newPath()
    private var selected = -1

    val landingXs = FloatArray(flights.size)
    val landingYs = FloatArray(flights.size)

    /** Each landing dot's palette colour index. */
    val landingColorIndices: IntArray
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
        val colorIndices = flights.map { it.colorIndex.mod(paletteSize) }.distinct().sorted()
        groupColorIndices = colorIndices.toIntArray()
        groupPaths = colorIndices.map { newPath() }
        groupOf = IntArray(flights.size) { colorIndices.indexOf(flights[it].colorIndex.mod(paletteSize)) }
        landingColorIndices = IntArray(flights.size) { flights[it].colorIndex.mod(paletteSize) }
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
        for (index in groupPaths.indices) groupPaths[index].rewind()
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
        path: P,
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
}
