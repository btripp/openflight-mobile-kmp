// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.location

/**
 * How precise a [LocationProvider.current] fix should be. Plan F6 (§0.3): the F-series only ever
 * asks for [COARSE] (city-block precision is enough to pick a weather station); [FINE] exists for
 * a later, explicit "course mode" request and isn't wired to a permission by this step.
 */
enum class LocationAccuracy {
    COARSE,
    FINE,
}

/**
 * A single location fix, or why one couldn't be produced. Plan F6 (§1): pure data, so `core:data`
 * can consume it without either platform's location types leaking into its public API.
 */
sealed interface LocationResult {
    /**
     * @property lat latitude in degrees.
     * @property lon longitude in degrees.
     * @property altitudeM the fix's altitude above sea level, when the platform reported one.
     * @property accuracyM the horizontal accuracy radius, in metres.
     */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val altitudeM: Double?,
        val accuracyM: Double,
    ) : LocationResult

    /** The permission for the requested [LocationAccuracy] isn't granted. */
    data object PermissionDenied : LocationResult

    /** Location services are off system-wide (Android's location setting, or iOS with it disabled). */
    data object Disabled : LocationResult

    /** Permission is granted and location is on, but no fix could be produced. */
    data object Unavailable : LocationResult
}

/**
 * One-shot device location (plan F6, §0.3 and §1). Implementations never keep a location
 * subscription open: each call to [current] starts and finishes its own request.
 *
 * - Android: `LocationManagerCompat.getCurrentLocation` (no Google Play Services dependency, so
 *   F-Droid builds stay dependency-free). It only **checks** the runtime permission; the OS
 *   prompt itself is a `ComponentActivity` concern (`ActivityResultContracts.RequestPermission`),
 *   fired by the "Use my location" action before this is called, because showing that dialog
 *   needs an `Activity` that a `core` module must not hold a reference to.
 * - iOS: `CLLocationManager`, one-shot. Unlike Android, `requestWhenInUseAuthorization()` **is**
 *   the permission prompt, so calling [current] both asks and fetches.
 *
 * Either platform's prompt is reachable only from the "Use my location" action (parent plan
 * §0.6): nothing in this module calls [current] on its own, and `core:data`'s
 * `ConditionsRepository` never refreshes in the background (plan F6 task 3).
 */
interface LocationProvider {
    suspend fun current(accuracy: LocationAccuracy = LocationAccuracy.COARSE): LocationResult
}
