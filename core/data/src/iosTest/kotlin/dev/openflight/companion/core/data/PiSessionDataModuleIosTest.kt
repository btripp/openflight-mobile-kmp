// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.openflight.companion.core.model.pi.PiLinkState
import org.koin.dsl.koinApplication
import kotlin.test.Test

/** The real iOS graph resolves the R6a Socket.IO repository (Darwin WebSocket transport) as a singleton. */
class PiSessionDataModuleIosTest {
    @Test
    fun dataModuleResolvesThePiSessionRepositoryAsASingleton() {
        val app = koinApplication { modules(dataModule) }
        try {
            val repository = app.koin.get<PiSessionRepository>()
            assertThat(app.koin.get<PiSessionRepository>()).isSameInstanceAs(repository)
            assertThat(repository.linkState.value).isEqualTo(PiLinkState.Idle)
        } finally {
            app.close()
        }
    }
}
