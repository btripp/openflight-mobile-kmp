// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

/**
 * Where the shared range geometry writes its projected outlines (plan F8c1): a platform path,
 * retained between frames and rewritten in place, so nothing allocates per frame.
 *
 * Every shape ([WorldPolygon], [TracerRibbon], [OverlayGeometry]) owns its sinks (one per path it
 * draws), made once by the renderer's factory. On re-projection a shape calls [rewind] and then
 * emits one or more subpaths with [moveTo], [lineTo] and, for filled outlines, [close]. The platform
 * then draws each sink with the shape's [RangeColor] from [RangeVisualStyle]: filled polygons and
 * tracer ribbons (non-zero winding; every outline is simple), and stroked overlay trajectories.
 *
 * Android backs it with a Compose `Path`; iOS (plan F8c2) with a `CGMutablePath`.
 */
interface PathSink {
    /** Empties the path, keeping its storage. */
    fun rewind()

    fun moveTo(
        x: Float,
        y: Float,
    )

    fun lineTo(
        x: Float,
        y: Float,
    )

    /** Closes the current subpath back to its [moveTo] point. */
    fun close()
}
