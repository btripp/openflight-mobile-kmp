// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Plan F8f: `--preview-profiles` reports the preview history's two people as the Pi's roster. */
class PreviewProfilesPiSessionRepositoryTest {
    @Test
    fun theRosterIsThePreviewHistorysProfilesWithAnnActive() =
        runTest {
            val repository = PreviewProfilesPiSessionRepository(FakePiSessionRepository())
            val roster = repository.profiles.value

            assertThat(roster.loaded).isTrue()
            assertThat(roster.profiles.map { it.id }).containsExactly("ann", "bo")
            assertThat(roster.activeProfileId).isEqualTo("ann")

            repository.setActiveProfile("bo")
            assertThat(repository.profiles.value.activeProfileId).isEqualTo("bo")
            repository.setActiveProfile("nobody")
            assertThat(repository.profiles.value.activeProfileId).isEqualTo("bo")
        }
}
