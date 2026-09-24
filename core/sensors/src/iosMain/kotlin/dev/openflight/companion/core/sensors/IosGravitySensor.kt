// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import dev.openflight.companion.core.model.GravitySample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMAttitudeReferenceFrameXArbitraryZVertical
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue

/** Builds the iOS [GravitySensor]. */
fun createGravitySensor(): GravitySensor = IosGravitySensor()

/**
 * CoreMotion gravity source, ported from the platform half of
 * `ios/OpenFlight/PhoneOrientationMonitor.swift`: device-motion updates in the
 * `xArbitraryZVertical` frame on the main queue. `motion.gravity` is emitted unchanged, since it
 * already uses the wire convention.
 */
class IosGravitySensor(
    private val motionManager: CMMotionManager = CMMotionManager(),
) : GravitySensor {
    override val isAvailable: Boolean
        get() = motionManager.deviceMotionAvailable

    @OptIn(ExperimentalForeignApi::class)
    override fun samples(hz: Int): Flow<GravitySample> =
        callbackFlow {
            if (!motionManager.deviceMotionAvailable) {
                close(GravitySensorException(UNAVAILABLE_MESSAGE))
                return@callbackFlow
            }
            motionManager.deviceMotionUpdateInterval = 1.0 / hz.coerceAtLeast(1)
            motionManager.startDeviceMotionUpdatesUsingReferenceFrame(
                CMAttitudeReferenceFrameXArbitraryZVertical,
                NSOperationQueue.mainQueue,
            ) { motion, error ->
                if (error != null) {
                    close(GravitySensorException(error.localizedDescription))
                    return@startDeviceMotionUpdatesUsingReferenceFrame
                }
                val sample = motion?.gravity?.useContents { GravitySample(x, y, z) }
                if (sample != null) trySend(sample)
            }
            awaitClose { motionManager.stopDeviceMotionUpdates() }
        }

    private companion object {
        const val UNAVAILABLE_MESSAGE = "Motion sensing is unavailable on this device. Use a physical iPhone."
    }
}
