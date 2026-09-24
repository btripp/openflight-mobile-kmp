// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import dev.openflight.companion.core.sensors.createGravitySensor
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformCalibrationModule: Module =
    module {
        single { createGravitySensor() }
    }
