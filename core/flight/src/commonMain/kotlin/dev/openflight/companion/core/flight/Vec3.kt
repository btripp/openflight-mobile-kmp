// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.sqrt

/**
 * A minimal 3D double vector with the arithmetic operators the ball-flight math needs, standing
 * in for Swift's `SIMD3<Double>` (ported from the Swift files under `ios/OpenFlight/DrivingRange`,
 * which use `simd`). x is downrange-lateral (left/right), y is vertical, z is downrange distance,
 * matching the reference's axis convention.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    operator fun times(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    operator fun div(scalar: Double): Vec3 = Vec3(x / scalar, y / scalar, z / scalar)

    infix fun cross(other: Vec3): Vec3 =
        Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun length(): Double = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

operator fun Double.times(vector: Vec3): Vec3 = vector * this
