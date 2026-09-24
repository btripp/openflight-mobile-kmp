// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.openflight.companion.core.model.GravitySample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Builds the Android [GravitySensor]. S6/S8c wire this through Koin with the application context. */
fun createGravitySensor(context: Context): GravitySensor = AndroidGravitySensor(context.applicationContext)

/**
 * `SensorManager` gravity source. Uses `TYPE_GRAVITY`, and falls back to `TYPE_ACCELEROMETER`
 * through [LowPassGravityFilter] when the device has no gravity sensor. Every reading goes through
 * [androidGravityToCoreMotion] before it is emitted.
 */
class AndroidGravitySensor(
    context: Context,
) : GravitySensor {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val isAvailable: Boolean
        get() = gravitySensor != null || accelerometer != null

    override fun samples(hz: Int): Flow<GravitySample> =
        callbackFlow {
            val manager = sensorManager
            val sensor = gravitySensor ?: accelerometer
            if (manager == null || sensor == null) {
                close(GravitySensorException("Motion sensing is unavailable on this device."))
                return@callbackFlow
            }
            val lowPass = if (sensor.type == Sensor.TYPE_ACCELEROMETER) LowPassGravityFilter() else null
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val rawX = event.values[0].toDouble()
                        val rawY = event.values[1].toDouble()
                        val rawZ = event.values[2].toDouble()
                        val (x, y, z) = lowPass?.filter(rawX, rawY, rawZ) ?: Triple(rawX, rawY, rawZ)
                        trySend(androidGravityToCoreMotion(x, y, z))
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor,
                        accuracy: Int,
                    ) = Unit
                }
            // 60 Hz -> 16 667 µs; the rate is a hint the platform may exceed.
            val registered = manager.registerListener(listener, sensor, MICROS_PER_SECOND / hz.coerceAtLeast(1))
            if (!registered) {
                close(GravitySensorException("Couldn't start the motion sensor."))
                return@callbackFlow
            }
            awaitClose { manager.unregisterListener(listener) }
        }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000
    }
}
