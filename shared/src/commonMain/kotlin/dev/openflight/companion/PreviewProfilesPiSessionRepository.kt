// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Plan F8f, the `--preview-profiles` launch hook: [delegate] with the roster of the
 * `--preview-history` shots (Ann, active, and Bo, the ids their rows are filed under), so the
 * range's "Viewing profile" can be shown and UI-tested with two people sharing one session.
 * [setActiveProfile] switches the preview roster only.
 */
internal class PreviewProfilesPiSessionRepository(
    private val delegate: PiSessionRepository,
) : PiSessionRepository by delegate {
    private val roster =
        MutableStateFlow(
            ProfilesState(
                profiles = listOf(Profile(id = ANN, name = "Ann"), Profile(id = BO, name = "Bo")),
                activeProfileId = ANN,
                loaded = true,
            ),
        )

    override val profiles: StateFlow<ProfilesState> = roster

    override suspend fun setActiveProfile(profileId: String) {
        roster.update { state ->
            if (state.profiles.any { it.id == profileId }) state.copy(activeProfileId = profileId) else state
        }
    }

    companion object {
        /** The profile ids [PreviewShotHistoryRepository]'s current session files its shots under. */
        const val ANN = "ann"
        const val BO = "bo"
    }
}
