// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import org.koin.dsl.koinApplication
import kotlin.test.Test

/** The real iOS graph resolves: platform bindings, HttpClient, factories and both repositories. */
class DataModuleIosTest {
    @Test
    fun dataModuleResolvesBothRepositoriesAsSingletons() {
        val app = koinApplication { modules(dataModule) }
        try {
            val koin = app.koin
            assertThat(koin.get<ShotRepository>()).isSameInstanceAs(koin.get<ShotRepository>())
            assertThat(koin.get<SettingsRepository>()).isSameInstanceAs(koin.get<SettingsRepository>())
            // F3: the history repositories share one database; the game and final-shot seams.
            assertThat(koin.get<ShotHistoryRepository>()).isSameInstanceAs(koin.get<ShotHistoryRepository>())
            assertThat(koin.get<BagRepository>()).isSameInstanceAs(koin.get<BagRepository>())
            assertThat(koin.get<ActivityRepository>()).isSameInstanceAs(koin.get<ActivityRepository>())
            assertThat(koin.get<ActiveGameRepository>()).isSameInstanceAs(koin.get<ActiveGameRepository>())
            assertThat(koin.get<FinalShotStream>()).isSameInstanceAs(koin.get<FinalShotStream>())
        } finally {
            app.close()
        }
    }
}
