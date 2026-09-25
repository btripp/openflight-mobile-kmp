// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

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

    /** Location + weather (plan F6). Until F6 lands, it behaves like [MANUAL]. */
    AUTO("auto"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ConditionsMode? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * The playing conditions that distance estimates are adjusted for (plan F2, A2). Starts at
 * [Conditions.ISA] (the air the server's carry already assumes) until the user sets something.
 */
interface ConditionsRepository {
    /** The current conditions, [Conditions.ISA] by default. */
    val conditions: StateFlow<Conditions>

    val mode: StateFlow<ConditionsMode>

    /** The direction the user hits toward; `null` until set, and wind is ignored until then. */
    val targetBearing: StateFlow<TargetBearing?>

    /** Stores [conditions] as the manual conditions and switches to [ConditionsMode.MANUAL]. */
    suspend fun setManual(conditions: Conditions)

    /** Sets or clears ([bearing] `null`) the target bearing. */
    suspend fun setTargetBearing(bearing: TargetBearing?)

    /** Changes only the landing-area firmness. */
    suspend fun setSurface(surface: Firmness)

    /** Re-reads automatic conditions; a no-op in [ConditionsMode.MANUAL]. */
    suspend fun refresh()
}
