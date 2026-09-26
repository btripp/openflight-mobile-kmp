// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual conditions kept in memory for the app flow tests. The real one shares the settings
 * DataStore file, and a second DataStore on that file (each test restarts Koin) throws; plan F5's
 * Bag screen is the first UI that reads conditions.
 */
internal class InMemoryConditionsRepository : ConditionsRepository {
    override val conditions = MutableStateFlow(Conditions.ISA)
    override val mode = MutableStateFlow(ConditionsMode.MANUAL)
    override val targetBearing = MutableStateFlow<TargetBearing?>(null)

    override suspend fun setManual(conditions: Conditions) {
        mode.value = ConditionsMode.MANUAL
        this.conditions.value = conditions
    }

    override suspend fun setTargetBearing(bearing: TargetBearing?) {
        targetBearing.value = bearing
    }

    override suspend fun setSurface(surface: Firmness) {
        conditions.value = conditions.value.copy(surface = surface)
    }

    override suspend fun refresh() = Unit
}
