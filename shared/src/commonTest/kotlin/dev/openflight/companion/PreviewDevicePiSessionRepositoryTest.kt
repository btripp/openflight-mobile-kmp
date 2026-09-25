// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.pi.PiLinkState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The `--preview-pi` roster follows the Pi's profile rules (plan R8f). */
class PreviewDevicePiSessionRepositoryTest {
    private val repository = PreviewDevicePiSessionRepository()

    private fun names() =
        repository.profiles.value.profiles
            .map { it.name }

    @Test
    fun itIsAConnectedPiWithThreeProfiles() {
        assertThat(repository.linkState.value).isEqualTo(PiLinkState.Connected)
        assertThat(names()).containsExactly("Ann", "Bob", "Cara")
        assertThat(
            repository.profiles.value.activeProfile
                ?.name,
        ).isEqualTo("Ann")
    }

    @Test
    fun addingATrimmedNameMakesItActive() =
        runTest {
            repository.addProfile("  Dee ")

            assertThat(
                repository.profiles.value.activeProfile
                    ?.name,
            ).isEqualTo("Dee")
        }

    @Test
    fun theActiveProfileIsKeptAndAnotherIsRemoved() =
        runTest {
            repository.removeProfile("preview-ann")
            repository.removeProfile("preview-cara")

            assertThat(names()).containsExactly("Ann", "Bob")
        }
}
