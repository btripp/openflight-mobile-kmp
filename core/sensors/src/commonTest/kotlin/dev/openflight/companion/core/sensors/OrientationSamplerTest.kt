// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.GravitySample
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

class OrientationSamplerTest {
    private val level = GravitySample(0.0, -1.0, 0.0)
    private val outOfBand = GravitySample(0.0, -2.0, 0.0)
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000)
        }

    private fun sampler() = OrientationSampler(deviceModel = "Pixel 9", clock = fixedClock)

    @Test
    fun measurementAppearsOnlyAtTheHundredAndTwentiethSample() {
        val sampler = sampler()

        repeat(119) { assertThat(sampler.add(level).measurement).isNull() }
        val state = sampler.add(level)

        assertThat(state.sampleCount).isEqualTo(120)
        assertThat(state.progress).isEqualTo(1.0)
        assertThat(state.measurement).isNotNull()
        assertThat(state.measurement!!.deviceModel).isEqualTo("Pixel 9")
        assertThat(state.measurement.measuredAt).isEqualTo("2023-11-14T22:13:20Z")
        assertThat(state.displayAngles!!.isStableAverage).isTrue()
    }

    @Test
    fun progressIsSampleCountOverOneHundredTwentyCappedAtOne() {
        val sampler = sampler()

        repeat(59) { sampler.add(level) }
        assertThat(sampler.add(level).progress).isCloseTo(0.5, 1e-12)
        repeat(200) { sampler.add(level) }
        assertThat(sampler.add(level).progress).isEqualTo(1.0)
    }

    @Test
    fun windowKeepsOnlyTheHundredAndTwentyMostRecentRawSamples() {
        val sampler = sampler()

        repeat(500) { sampler.add(level) }

        assertThat(sampler.add(level).sampleCount).isEqualTo(120)
    }

    @Test
    fun oneOutOfBandSampleBlocksTheMeasurementUntilItScrollsOut() {
        val sampler = sampler()
        repeat(120) { sampler.add(level) }

        // The out-of-band sample counts toward the raw window but is filtered inside it.
        val blocked = sampler.add(outOfBand)
        assertThat(blocked.sampleCount).isEqualTo(120)
        assertThat(blocked.measurement).isNull()
        repeat(119) { assertThat(sampler.add(level).measurement).isNull() }

        // The 120th sample after it pushes it out of the window.
        val recovered = sampler.add(level)
        assertThat(recovered.measurement).isNotNull()
        assertThat(recovered.measurement!!.sampleCount).isEqualTo(120)
    }

    @Test
    fun resetClearsTheWindow() {
        val sampler = sampler()
        repeat(120) { sampler.add(level) }

        sampler.reset()

        val state = sampler.add(level)
        assertThat(state.sampleCount).isEqualTo(1)
        assertThat(state.measurement).isNull()
    }

    @Test
    fun displayShowsLiveAnglesBeforeTheWindowFills() {
        val state = sampler().add(level)

        assertThat(state.displayAngles).isNotNull()
        assertThat(state.displayAngles!!.mountTiltDegrees).isCloseTo(0.0, 1e-9)
        assertThat(state.displayAngles.isStableAverage).isFalse()
    }

    @Test
    fun orientationStatesStartsWithInitialAndEmitsOneStatePerSample() =
        runTest {
            List(120) { level }.asFlow().orientationStates("Pixel 9", fixedClock).test {
                assertThat(awaitItem()).isEqualTo(OrientationSamplerState.Initial)
                repeat(119) { assertThat(awaitItem().measurement).isNull() }
                val last = awaitItem()
                assertThat(last.sampleCount).isEqualTo(120)
                assertThat(last.measurement!!.isReadyToSend).isTrue()
                awaitComplete()
            }
        }

    @Test
    fun initialStateHasZeroProgress() {
        assertThat(OrientationSamplerState.Initial.progress).isEqualTo(0.0)
        assertThat(OrientationSamplerState.Initial.displayAngles).isNull()
    }
}
