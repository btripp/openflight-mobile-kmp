// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import dev.openflight.companion.core.model.GravitySample

/** Standard gravity in m/s² (the value Android exposes as `SensorManager.STANDARD_GRAVITY`). */
const val STANDARD_GRAVITY_M_S2: Double = 9.80665

/**
 * Converts an Android `TYPE_GRAVITY` (or low-passed `TYPE_ACCELEROMETER`) reading in m/s² into
 * the iOS CoreMotion convention used on the wire: `g_ios = -g_android / 9.80665`.
 *
 * Documentation evidence (fetched 2026-09-24):
 * - Android `SensorEvent` reference (developer.android.com/reference/android/hardware/SensorEvent):
 *   "The X axis is horizontal and points to the right, the Y axis is vertical and points up and
 *   the Z axis points towards the outside of the front face of the screen." For
 *   `TYPE_ACCELEROMETER`: "When the device lies flat on a table, the acceleration value is
 *   +9.81". For `TYPE_GRAVITY`: "Units are m/s^2. The coordinate system is the same as is used by
 *   the acceleration sensor … When the device is at rest, the output of the gravity sensor should
 *   be identical to that of the accelerometer." So Android face-up reads z ≈ +9.81 m/s², and the
 *   vector points *away from* Earth (it is the support reaction).
 * - Apple `UIAcceleration` (developer.apple.com/documentation/uikit/uiacceleration): values are in
 *   units of g, and "When a device is laying still with its back on a horizontal surface" it reads
 *   `x: 0, y: 0, z: -1`. Apple `CMDeviceMotion.gravity`: "The gravity acceleration vector expressed
 *   in the device's reference frame", where "The total acceleration of the device is equal to
 *   gravity plus the acceleration the user imparts to the device (userAcceleration)". So at rest
 *   CoreMotion gravity equals the raw acceleration: face-up z ≈ -1 g, pointing *toward* Earth.
 * - Apple documents the device axes only as an illustration (`CMMotionManager`: "positive x-axis,
 *   positive y-axis, and positive z-axis"). The reference iOS tests (`PhoneOrientationTests.swift`)
 *   pin portrait-upright as `(0, -1, 0)`, meaning +y runs toward the top edge, as it does on Android.
 *
 * Both platforms therefore share axes (x right, y toward the top edge, z out of the screen) and
 * differ by sign and units. The x-axis direction on iOS has no textual source; it is inferred
 * from the shared right-handed frame and still needs a physical-device check
 * (plan Step 8b, BLOCKED-ON-HARDWARE).
 */
fun androidGravityToCoreMotion(
    x: Double,
    y: Double,
    z: Double,
): GravitySample =
    GravitySample(
        x = -x / STANDARD_GRAVITY_M_S2,
        y = -y / STANDARD_GRAVITY_M_S2,
        z = -z / STANDARD_GRAVITY_M_S2,
    )

/**
 * The low-pass filter from the Android `SensorEvent` docs (`alpha = 0.8`), used to estimate
 * gravity from `TYPE_ACCELEROMETER` when a device has no `TYPE_GRAVITY` sensor. Works in the
 * input's own units and convention.
 *
 * Unlike the docs example, which starts from zero, the first reading seeds the filter. Starting
 * from zero would feed about a dozen near-zero-magnitude samples into the raw window, and the
 * calculator's 0.8–1.2 g filter would then reject them.
 */
class LowPassGravityFilter(
    private val alpha: Double = DEFAULT_ALPHA,
) {
    private var gravity: DoubleArray? = null

    fun filter(
        x: Double,
        y: Double,
        z: Double,
    ): Triple<Double, Double, Double> {
        val previous = gravity
        val next =
            if (previous == null) {
                doubleArrayOf(x, y, z)
            } else {
                doubleArrayOf(
                    alpha * previous[0] + (1 - alpha) * x,
                    alpha * previous[1] + (1 - alpha) * y,
                    alpha * previous[2] + (1 - alpha) * z,
                )
            }
        gravity = next
        return Triple(next[0], next[1], next[2])
    }

    companion object {
        const val DEFAULT_ALPHA = 0.8
    }
}
