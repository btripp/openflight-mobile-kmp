// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.feature.range.RangeCameraRig
import dev.openflight.companion.feature.range.RangeFrame
import dev.openflight.companion.feature.range.RangeTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPathGetBoundingBox
import platform.CoreGraphics.CGPathIsEmpty
import kotlin.test.Test

@OptIn(ExperimentalForeignApi::class)
class CGPathSinkTest {
    @Test
    fun writesTheOutlineIntoItsPath() {
        val sink = CGPathSink()

        sink.moveTo(10f, 20f)
        sink.lineTo(110f, 20f)
        sink.lineTo(110f, 70f)
        sink.close()

        assertThat(CGPathIsEmpty(sink.path)).isFalse()
        CGPathGetBoundingBox(sink.path).useContents {
            assertThat(origin.x).isEqualTo(10.0)
            assertThat(origin.y).isEqualTo(20.0)
            assertThat(size.width).isEqualTo(100.0)
            assertThat(size.height).isEqualTo(50.0)
        }
    }

    @Test
    fun rewindEmptiesThePath() {
        val sink = CGPathSink()
        sink.moveTo(0f, 0f)
        sink.lineTo(5f, 5f)

        sink.rewind()

        assertThat(CGPathIsEmpty(sink.path)).isTrue()
        sink.moveTo(1f, 2f)
        sink.lineTo(3f, 4f)
        CGPathGetBoundingBox(sink.path).useContents { assertThat(origin.x).isEqualTo(1.0) }
    }

    @Test
    fun aRangeFrameProjectsTheSceneIntoCGPaths() {
        val rig = RangeCameraRig()
        val frame = RangeFrame(RangeTheme.DAY.style, clubPaletteSize = 8, newPath = { CGPathSink() })

        frame.resize(1206f, 2622f, rig.fixedPose)
        assertThat(frame.prepare(rig.fixedPose, 0f)).isTrue()

        val fairway = frame.scene.fairway
        assertThat(fairway.visible).isTrue()
        assertThat(fairway.path.path).isNotNull()
        assertThat(CGPathIsEmpty(fairway.path.path)).isFalse()
    }
}
