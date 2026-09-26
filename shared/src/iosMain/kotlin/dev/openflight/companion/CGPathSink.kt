// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.feature.range.PathSink
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGMutablePathRef
import platform.CoreGraphics.CGPathAddLineToPoint
import platform.CoreGraphics.CGPathCloseSubpath
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathIsEmpty
import platform.CoreGraphics.CGPathMoveToPoint
import platform.CoreGraphics.CGPathRelease
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

/**
 * The shared range geometry's [PathSink] on iOS (plan F8c2, ADR 0002): the per-point loop of
 * `RangeScene`, `TracerRibbon` and `OverlayGeometry` writes straight into a CoreGraphics
 * `CGMutablePath` here, in Kotlin, and Swift fills the finished [path] once per shape
 * (`RangeCanvasView`, `Path(cgPath)`).
 *
 * A `CGMutablePath` can't be emptied in place, so [rewind] swaps in a fresh one, and only when the
 * current one holds something: a shape the camera didn't move is never rebuilt. The path that
 * Swift is drawing stays alive, since Swift retains its own reference.
 */
@OptIn(ExperimentalForeignApi::class)
class CGPathSink : PathSink {
    /** Owns the CoreGraphics reference, released when this sink is collected. */
    private class Holder {
        var path: CGMutablePathRef? = CGPathCreateMutable()

        fun release() {
            CGPathRelease(path)
            path = null
        }
    }

    private val holder = Holder()

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused") // Held only for its release when the sink is collected.
    private val cleaner: Cleaner = createCleaner(holder) { it.release() }

    /**
     * The outline written since the last [rewind], in the frame's canvas coordinates. Objective-C
     * sees an opaque pointer; Swift reads it as a `CGPath` through `CGPathSink.cgPath`
     * (`Unmanaged.fromOpaque(...).takeUnretainedValue()`, which retains it while drawn).
     */
    val path: CGMutablePathRef? get() = holder.path

    override fun rewind() {
        val current = holder.path
        if (current != null && CGPathIsEmpty(current)) return
        holder.path = CGPathCreateMutable()
        CGPathRelease(current)
    }

    override fun moveTo(
        x: Float,
        y: Float,
    ) {
        CGPathMoveToPoint(holder.path, null, x.toDouble(), y.toDouble())
    }

    override fun lineTo(
        x: Float,
        y: Float,
    ) {
        CGPathAddLineToPoint(holder.path, null, x.toDouble(), y.toDouble())
    }

    override fun close() {
        CGPathCloseSubpath(holder.path)
    }
}
