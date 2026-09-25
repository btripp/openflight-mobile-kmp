// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.flow.MutableStateFlow

/** A [ConditionsRepository] over plain state flows; [refreshCount] records [refresh] calls. */
class FakeConditionsRepository(
    conditions: Conditions = Conditions.ISA,
    targetBearing: TargetBearing? = null,
    mode: ConditionsMode = ConditionsMode.MANUAL,
) : ConditionsRepository {
    override val conditions = MutableStateFlow(conditions)
    override val mode = MutableStateFlow(mode)
    override val targetBearing = MutableStateFlow(targetBearing)
    var refreshCount = 0
        private set

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

    override suspend fun refresh() {
        refreshCount += 1
    }
}
