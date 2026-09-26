// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.ConditionsError
import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [ConditionsRepository] over plain state flows; [refreshCount] records [refresh] calls, and
 * [setModeResult] lets a test script what a subsequent [setMode] to `AUTO` should do (mirroring
 * `DataStoreConditionsRepository.fetchAuto`, without a real `LocationProvider`/`WeatherClient`).
 */
class FakeConditionsRepository(
    conditions: Conditions = Conditions.ISA,
    targetBearing: TargetBearing? = null,
    mode: ConditionsMode = ConditionsMode.MANUAL,
) : ConditionsRepository {
    override val conditions = MutableStateFlow(conditions)
    override val mode = MutableStateFlow(mode)
    override val targetBearing = MutableStateFlow(targetBearing)
    override val lastError = MutableStateFlow<ConditionsError?>(null)
    override val lastLocation = MutableStateFlow<LocationResult.Fix?>(null)
    var refreshCount = 0
        private set

    /** What the next [setMode]`(AUTO)` (or [refresh] while already AUTO) applies; `null` is a no-op. */
    var setModeResult: (() -> Unit)? = null

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

    override suspend fun setMode(mode: ConditionsMode) {
        this.mode.value = mode
        if (mode == ConditionsMode.AUTO) setModeResult?.invoke()
    }

    override suspend fun refresh() {
        refreshCount += 1
        if (mode.value == ConditionsMode.AUTO) setModeResult?.invoke()
    }
}
