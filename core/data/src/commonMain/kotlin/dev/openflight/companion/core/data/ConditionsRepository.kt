// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.flow.StateFlow

/** Where [ConditionsRepository.conditions] comes from. */
enum class ConditionsMode(
    /** The value persisted in settings. */
    val storageValue: String,
) {
    /** The user's typed-in values ([ConditionsRepository.setManual]). */
    MANUAL("manual"),

    /** Location + weather (plan F6): [ConditionsRepository.setMode] drives the fetch. */
    AUTO("auto"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ConditionsMode? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * Why the last [ConditionsMode.AUTO] fetch didn't produce a fresh [Conditions] (plan F6). The UI
 * shows [userMessage]; [conditions] still holds the last known-good value.
 */
sealed interface ConditionsError {
    val userMessage: String

    /** The location permission [ConditionsRepository.setMode] needs for AUTO isn't granted. */
    data object LocationPermissionDenied : ConditionsError {
        override val userMessage: String =
            "Location permission is off, so conditions can't be set automatically. " +
                "Allow location access for OpenFlight in Settings, or enter conditions manually."
    }

    /** The device's location service (not just the app's permission) is off. */
    data object LocationDisabled : ConditionsError {
        override val userMessage: String = "Turn on location services to set conditions automatically."
    }

    /** Permission is granted and location is on, but no fix could be produced. */
    data object LocationUnavailable : ConditionsError {
        override val userMessage: String = "Couldn't get your location. Try again, or enter conditions manually."
    }

    /** The location fix worked, but the weather call failed; [reason] is a short, user-facing sentence. */
    data class WeatherUnavailable(
        val reason: String,
    ) : ConditionsError {
        override val userMessage: String = "Couldn't reach the weather service: $reason"
    }
}

/**
 * The playing conditions that distance estimates are adjusted for (plan F2 A2, extended by F6).
 * Starts at [Conditions.ISA] (the air the server's carry already assumes) until the user sets
 * something.
 */
interface ConditionsRepository {
    /** The current conditions: the manual value in [ConditionsMode.MANUAL], the last AUTO fetch otherwise. */
    val conditions: StateFlow<Conditions>

    val mode: StateFlow<ConditionsMode>

    /** The direction the user hits toward; `null` until set, and wind is ignored until then. */
    val targetBearing: StateFlow<TargetBearing?>

    /** Why the last AUTO fetch fell back to the previous [conditions], or `null` after a clean one. */
    val lastError: StateFlow<ConditionsError?>

    /** The fix behind the current AUTO [conditions], for a "near lat, lon" label; `null` in MANUAL. */
    val lastLocation: StateFlow<LocationResult.Fix?>

    /** Stores [conditions] as the manual conditions and switches to [ConditionsMode.MANUAL]. */
    suspend fun setManual(conditions: Conditions)

    /** Sets or clears ([bearing] `null`) the target bearing. */
    suspend fun setTargetBearing(bearing: TargetBearing?)

    /** Changes only the landing-area firmness. */
    suspend fun setSurface(surface: Firmness)

    /**
     * Switches to [mode]. Entering [ConditionsMode.AUTO] immediately fetches (plan F6 task 3),
     * which is the only place location or weather is touched -- the "Use my location" action calls
     * this, and nothing else does. A denied or disabled location falls back to
     * [ConditionsMode.MANUAL] with [lastError] set; a weather failure stays in AUTO showing the
     * last good value, since it's likely transient.
     */
    suspend fun setMode(mode: ConditionsMode)

    /** Re-fetches in [ConditionsMode.AUTO]; a no-op in [ConditionsMode.MANUAL]. Never automatic. */
    suspend fun refresh()
}
