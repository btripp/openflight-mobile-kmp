// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test

/** Ported one-to-one from `ios/OpenFlightTests/RangeSceneDescriptionTests.swift`. */
class RangeSceneDescriptionTest {
    @Test
    fun standardSceneHasExpectedRangeMarkers() {
        val scene = RangeSceneDescription.standard(treeCount = 20)

        assertThat(scene.markers.map { it.yards }).isEqualTo(listOf(50, 100, 150, 200, 250, 300, 350))
        assertThat(scene.rangeDepthMeters).isEqualTo(390.0)
        assertThat(scene.fairwayWidthMeters).isGreaterThan(40.0)
    }

    @Test
    fun treePopulationIsBoundedAndPlacedOutsideFairway() {
        val scene = RangeSceneDescription.standard(treeCount = 36)

        assertThat(scene.trees).hasSize(36)
        assertThat(scene.trees.all { kotlin.math.abs(it.xMeters) >= 32 }).isTrue()
        assertThat(scene.trees.all { it.downrangeMeters >= 22 }).isTrue()
    }

    @Test
    fun qualityProfilesBoundReusableResources() {
        assertThat(RangeQualityProfile.BALANCED.treeCount).isLessThan(RangeQualityProfile.HIGH.treeCount)
        assertThat(RangeQualityProfile.BALANCED.tracerPointCount)
            .isLessThan(RangeQualityProfile.HIGH.tracerPointCount)
        assertThat(RangeQualityProfile.HIGH.tracerPointCount).isLessThanOrEqualTo(120)
    }

    @Test
    fun continuousTracerIsBlueTranslucentAndCompensatesForDistance() {
        val style = RangeTracerStyle.highVisibility

        assertThat(style.nearWidthMeters).isEqualTo(0.080)
        assertThat(style.farWidthMeters).isEqualTo(0.52)
        assertThat(style.farWidthMeters).isGreaterThan(style.nearWidthMeters)
        assertThat(style.opacity).isGreaterThan(0.75)
        assertThat(style.opacity).isLessThan(1.0)
        assertThat(style.blue).isGreaterThan(style.red)
        assertThat(style.blue).isGreaterThan(style.green)
    }
}
