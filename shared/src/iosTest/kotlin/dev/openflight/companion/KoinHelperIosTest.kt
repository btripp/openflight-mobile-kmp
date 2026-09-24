// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.isNotNull
import org.koin.core.context.stopKoin
import kotlin.test.Test

/** The real iOS app graph builds every ViewModel Swift asks [KoinHelper] for (Koin wiring only fails at runtime). */
class KoinHelperIosTest {
    @Test
    fun everyViewModelGetterResolvesFromTheAppGraph() {
        initKoin()
        try {
            val helper = KoinHelper()

            assertThat(helper.dashboardViewModel()).isNotNull()
            assertThat(helper.calibrationViewModel()).isNotNull()
            assertThat(helper.drivingRangeViewModel()).isNotNull()
            assertThat(helper.sessionViewModel()).isNotNull()
            assertThat(helper.trainingViewModel()).isNotNull()
            assertThat(helper.cameraViewModel()).isNotNull()
            assertThat(helper.settingsViewModel()).isNotNull()
        } finally {
            stopKoin()
        }
    }
}
