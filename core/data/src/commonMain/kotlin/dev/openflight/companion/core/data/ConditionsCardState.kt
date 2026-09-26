// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.geodata.OPEN_METEO_ATTRIBUTION
import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.Wind
import kotlin.math.round

/**
 * Display-ready state for the conditions card F5 (bag) and F8a2 (range) each show (plan F6
 * task 4). Both features map [ConditionsRepository]'s flows through [conditionsCardState] instead
 * of each reformatting [Conditions] on its own; the actual layout (compact/expanded, the "Edit"
 * affordance) stays theirs, since neither feature depends on the other.
 *
 * @property locationLabel `"near <lat>, <lon>"`, rounded to two decimals, when the current
 *   conditions came from AUTO and a fix is known; `null` otherwise. Plan F6 task 4: no reverse
 *   geocoding, so this is the only location label there is (besides a user-entered course name,
 *   which is a later step's concern).
 * @property isAuto whether the source is [dev.openflight.companion.core.data.ConditionsMode.AUTO]
 *   (device + weather) rather than a manual entry.
 * @property isEstimatedSource whether [Conditions.source] isn't [ConditionsSource.MANUAL], for an
 *   "est." badge (plan §0.2: every estimated number carries its provenance into the UI).
 * @property errorMessage [ConditionsError.userMessage] from the last AUTO refresh, or `null` after
 *   a clean one or in MANUAL.
 * @property attribution the CC BY 4.0 credit line Open-Meteo's licence requires wherever its data
 *   is shown; the card's footer or an About screen.
 */
data class ConditionsCardState(
    val altitudeMeters: Double,
    val temperatureC: Double,
    val wind: Wind,
    val surface: Firmness,
    val isAuto: Boolean,
    val isEstimatedSource: Boolean,
    val locationLabel: String?,
    val errorMessage: String?,
    val attribution: String,
)

/**
 * Maps [ConditionsRepository]'s current flow values into a [ConditionsCardState]. Pure: no I/O, no
 * platform types, so a `feature:*:ui` can call it straight from a `collectAsState` without a
 * ViewModel round trip.
 */
fun conditionsCardState(
    conditions: Conditions,
    mode: ConditionsMode,
    lastError: ConditionsError?,
    lastLocation: LocationResult.Fix?,
): ConditionsCardState =
    ConditionsCardState(
        altitudeMeters = conditions.altitudeMeters,
        temperatureC = conditions.temperatureC,
        wind = conditions.wind,
        surface = conditions.surface,
        isAuto = mode == ConditionsMode.AUTO,
        isEstimatedSource = conditions.source != ConditionsSource.MANUAL,
        locationLabel =
            if (mode == ConditionsMode.AUTO && lastLocation != null) {
                "near ${lastLocation.lat.roundedTo2()}, ${lastLocation.lon.roundedTo2()}"
            } else {
                null
            },
        errorMessage = lastError?.userMessage,
        attribution = OPEN_METEO_ATTRIBUTION,
    )

private fun Double.roundedTo2(): Double = round(this * ROUNDING_FACTOR) / ROUNDING_FACTOR

private const val ROUNDING_FACTOR = 100.0
