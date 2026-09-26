// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** Builds the iOS [LocationProvider]. */
fun createLocationProvider(): LocationProvider = IosLocationProvider()

/**
 * `CLLocationManager`, one-shot per [current] call. Unlike Android,
 * `requestWhenInUseAuthorization()` **is** the OS permission prompt (there's no separate
 * "check, then let the UI prompt" split): when the app hasn't decided yet, this call itself shows
 * the system dialog, which is why [LocationProvider]'s contract only lets it run from the "Use my
 * location" action.
 */
internal class IosLocationProvider : LocationProvider {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun current(accuracy: LocationAccuracy): LocationResult {
        if (!CLLocationManager.locationServicesEnabled()) return LocationResult.Disabled
        return suspendCancellableCoroutine { continuation ->
            val manager = CLLocationManager()
            val delegate = OneShotDelegate(manager, continuation)
            manager.delegate = delegate
            manager.desiredAccuracy =
                if (accuracy == LocationAccuracy.FINE) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
            continuation.invokeOnCancellation { manager.delegate = null }
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusNotDetermined -> {
                    manager.requestWhenInUseAuthorization()
                }

                kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> {
                    delegate.finish(
                        LocationResult.PermissionDenied,
                    )
                }

                else -> {
                    manager.requestLocation()
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class OneShotDelegate(
    private val manager: CLLocationManager,
    private val continuation: CancellableContinuation<LocationResult>,
) : NSObject(),
    CLLocationManagerDelegateProtocol {
    private var finished = false

    fun finish(result: LocationResult) {
        if (finished) return
        finished = true
        manager.delegate = null
        if (continuation.isActive) continuation.resume(result)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> {
                manager
                    .requestLocation()
            }

            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> {
                finish(LocationResult.PermissionDenied)
            }

            else -> {
                Unit
            } // still notDetermined; the user hasn't answered the prompt yet.
        }
    }

    override fun locationManager(
        manager: CLLocationManager,
        didUpdateLocations: List<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val location = (didUpdateLocations as List<CLLocation>).lastOrNull()
        val result =
            location?.let { fix ->
                fix.coordinate.useContents {
                    LocationResult.Fix(
                        lat = latitude,
                        lon = longitude,
                        altitudeM = fix.altitude,
                        accuracyM = fix.horizontalAccuracy,
                    )
                }
            } ?: LocationResult.Unavailable
        finish(result)
    }

    override fun locationManager(
        manager: CLLocationManager,
        didFailWithError: NSError,
    ) {
        finish(LocationResult.Unavailable)
    }
}
