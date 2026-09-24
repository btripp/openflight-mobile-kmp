// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import dev.openflight.companion.core.model.GravitySample
import kotlinx.coroutines.flow.Flow

/**
 * A platform gravity source. Every emitted [GravitySample] is already in the iOS CoreMotion
 * convention (units of g, pointing toward Earth, face-up `z ≈ -1`).
 *
 * This is an interface rather than an `expect class` so view models can be tested with fakes.
 * Platform implementations: `AndroidGravitySensor` (androidMain, built with
 * `createGravitySensor(context)`) and `IosGravitySensor` (iosMain, built with
 * `createGravitySensor()`).
 */
interface GravitySensor {
    /** False when the device lacks the hardware (for example the iOS simulator). */
    val isAvailable: Boolean

    /**
     * A cold flow of samples at roughly [hz] per second. Collecting it starts the sensor, and
     * cancelling the collection stops it. The flow fails with a [GravitySensorException] when the
     * sensor is unavailable or the platform reports an error.
     */
    fun samples(hz: Int = DEFAULT_SAMPLE_RATE_HZ): Flow<GravitySample>

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 60
    }
}

/** A platform sensor failure. The message is user-presentable. */
class GravitySensorException(
    message: String,
) : Exception(message)

/** The device model reported in `device_model`: `Build.MODEL` on Android, `UIDevice.model` on iOS. */
expect fun deviceModel(): String
