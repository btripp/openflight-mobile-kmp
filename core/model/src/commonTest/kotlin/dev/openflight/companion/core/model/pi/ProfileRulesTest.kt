// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * The server's profile rules (profiles.py:29-51) as the client enforces them, ported from the Expo
 * `ProfileNameForm.test.tsx` and `ProfilePicker.tsx` (`feat/profile-selection` 3b78a44) and the
 * pure parts of `useProfileStore.test.ts`.
 */
class ProfileRulesTest {
    @Test
    fun anEmptyNameIsRefused() {
        assertThat(ProfileRules.checkName("")).isEqualTo(ProfileNameCheck.Blank)
    }

    @Test
    fun aWhitespaceOnlyNameIsRefused() {
        assertThat(ProfileRules.checkName("   \t ")).isEqualTo(ProfileNameCheck.Blank)
    }

    @Test
    fun theNameIsHandedBackTrimmed() {
        assertThat(ProfileRules.checkName("  Sam  ")).isEqualTo(ProfileNameCheck.Valid("Sam"))
    }

    @Test
    fun namesStopAtTheLengthTheServerAccepts() {
        assertThat(ProfileRules.MAX_NAME_LENGTH).isEqualTo(40)
        assertThat(ProfileRules.checkName("A".repeat(40))).isEqualTo(ProfileNameCheck.Valid("A".repeat(40)))
        assertThat(ProfileRules.checkName("A".repeat(41))).isEqualTo(ProfileNameCheck.TooLong)
        // Surrounding whitespace doesn't count against the limit.
        assertThat(
            ProfileRules.checkName("  " + "A".repeat(40) + "  "),
        ).isEqualTo(ProfileNameCheck.Valid("A".repeat(40)))
    }

    @Test
    fun theServerHoldsAtMostTwelveProfiles() {
        assertThat(ProfileRules.MAX_PROFILES).isEqualTo(12)
        assertThat(roster(11).canAdd).isTrue()
        assertThat(roster(12).canAdd).isFalse()
    }

    @Test
    fun theRosterStartsEmptyAndNotYetLoaded() {
        val state = ProfilesState()

        assertThat(state.profiles).isEqualTo(emptyList())
        assertThat(state.activeProfileId).isEqualTo("")
        assertThat(state.loaded).isFalse()
        assertThat(state.activeProfile).isNull()
    }

    @Test
    fun theActiveProfileIsFoundById() {
        val state = roster(3).copy(activeProfileId = "p2")

        assertThat(state.activeProfile?.name).isEqualTo("P2")
        assertThat(state.copy(activeProfileId = "gone").activeProfile).isNull()
    }

    private fun roster(count: Int) =
        ProfilesState(
            profiles = (1..count).map { Profile(id = "p$it", name = "P$it") },
            activeProfileId = "p1",
            loaded = true,
        )
}
