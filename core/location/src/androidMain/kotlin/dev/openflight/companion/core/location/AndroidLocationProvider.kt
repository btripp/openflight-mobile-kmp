// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Builds the Android [LocationProvider]. Koin's Android module binds `createLocationProvider(androidContext())`. */
fun createLocationProvider(context: Context): LocationProvider = AndroidLocationProvider(context.applicationContext)

/**
 * `LocationManagerCompat.getCurrentLocation` (no Play Services `FusedLocationProviderClient`,
 * which keeps an F-Droid build dependency-free). This class never requests the runtime
 * permission itself -- see [LocationProvider]'s KDoc -- it only checks whether the permission
 * [accuracy] needs is already granted.
 */
internal class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {
    @Suppress("ReturnCount") // One early exit per unavailability reason reads clearer than nesting.
    override suspend fun current(accuracy: LocationAccuracy): LocationResult {
        val permission = accuracy.manifestPermission()
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            return LocationResult.PermissionDenied
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider = accuracy.provider()
        if (locationManager == null || !locationManager.isProviderEnabled(provider)) {
            return LocationResult.Disabled
        }
        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                cancellationSignal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                val result =
                    location?.let {
                        LocationResult.Fix(
                            lat = it.latitude,
                            lon = it.longitude,
                            altitudeM = it.altitude.takeIf { alt -> it.hasAltitude() && alt != 0.0 },
                            accuracyM = if (it.hasAccuracy()) it.accuracy.toDouble() else DEFAULT_ACCURACY_M,
                        )
                    } ?: LocationResult.Unavailable
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    /**
     * `FUSED_PROVIDER` (API 31+, part of AOSP, no Play Services) for either accuracy above that
     * level; below it, `NETWORK_PROVIDER` for [LocationAccuracy.COARSE] and `GPS_PROVIDER` for
     * [LocationAccuracy.FINE], matching the two runtime permissions.
     */
    private fun LocationAccuracy.provider(): String =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> LocationManager.FUSED_PROVIDER
            this == LocationAccuracy.FINE -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }

    private fun LocationAccuracy.manifestPermission(): String =
        when (this) {
            LocationAccuracy.COARSE -> Manifest.permission.ACCESS_COARSE_LOCATION
            LocationAccuracy.FINE -> Manifest.permission.ACCESS_FINE_LOCATION
        }

    private companion object {
        // LocationManagerCompat guarantees `hasAccuracy()` on API 26+ fixes, but a defensive
        // fallback keeps this from ever emitting a NaN into ConditionsRepository's Wind math.
        const val DEFAULT_ACCURACY_M = 100.0
    }
}
