// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import dev.openflight.companion.core.model.GravitySample
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.time.Clock

/**
 * What the calibration screen renders after each sample, ported from the published properties of
 * `ios/OpenFlight/PhoneOrientationMonitor.swift`. [sampleCount] is the number of **raw** samples
 * in the window, so it counts samples the magnitude filter later drops.
 */
data class OrientationSamplerState(
    val measurement: PhoneOrientationMeasurement?,
    val displayAngles: PhoneOrientationDisplayAngles?,
    val sampleCount: Int,
) {
    /** `min(1, sampleCount / 120)`, as in the reference monitor. */
    val progress: Double
        get() = min(1.0, sampleCount.toDouble() / PhoneOrientationCalculator.MINIMUM_SAMPLE_COUNT)

    companion object {
        val Initial = OrientationSamplerState(measurement = null, displayAngles = null, sampleCount = 0)
    }
}

/**
 * The platform-free half of `PhoneOrientationMonitor`. It keeps the window of the
 * [PhoneOrientationCalculator.MINIMUM_SAMPLE_COUNT] most recent **raw** samples, and recomputes
 * the measurement (which filters by magnitude *inside* that window) and the display angles on
 * every sample. Because the window holds raw samples, a single out-of-band sample blocks a
 * measurement until it scrolls out.
 *
 * Not thread-safe. Feed it from one coroutine, or use [orientationStates].
 */
class OrientationSampler(
    private val deviceModel: String,
    private val clock: Clock = Clock.System,
) {
    private val window = ArrayDeque<GravitySample>(PhoneOrientationCalculator.MINIMUM_SAMPLE_COUNT + 1)

    /** Clears the window, as `PhoneOrientationMonitor.start()` does. */
    fun reset() {
        window.clear()
    }

    fun add(sample: GravitySample): OrientationSamplerState {
        window.addLast(sample)
        while (window.size > PhoneOrientationCalculator.MINIMUM_SAMPLE_COUNT) window.removeFirst()
        val measurement =
            PhoneOrientationCalculator.measurement(
                samples = window.toList(),
                deviceModel = deviceModel,
                measuredAt = clock.now(),
            )
        return OrientationSamplerState(
            measurement = measurement,
            displayAngles = PhoneOrientationCalculator.displayAngles(sample, measurement),
            sampleCount = window.size,
        )
    }
}

/**
 * Maps a gravity stream to sampler states, starting with [OrientationSamplerState.Initial]. Each
 * collection gets a fresh window.
 */
fun Flow<GravitySample>.orientationStates(
    deviceModel: String,
    clock: Clock = Clock.System,
): Flow<OrientationSamplerState> {
    val upstream = this
    return flow {
        val sampler = OrientationSampler(deviceModel, clock)
        emit(OrientationSamplerState.Initial)
        upstream.collect { emit(sampler.add(it)) }
    }
}
