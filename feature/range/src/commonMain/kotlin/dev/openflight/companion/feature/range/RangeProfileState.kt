// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.ViewingProfile
import dev.openflight.companion.core.insights.isShotOfProfile
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState

/**
 * Plan F8f: whose shots the range shows. Pi profiles are global to the Pi (one active profile, and
 * each shot is stamped with it at detection), so two phones on one Pi share one session; each
 * device picks its own [viewing] instead of switching the Pi's active profile.
 *
 * @property viewing the device's choice, the persisted `SettingsRepository.viewingProfile`.
 * @property profiles the Pi's roster; empty when none is known (Bluetooth v1, no Pi, before the
 *   roster arrives), when the control hides and every shot shows.
 * @property activeProfileId the Pi's active profile.
 */
data class RangeProfileState(
    val viewing: ViewingProfile = ViewingProfile.FollowActive,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String = "",
) {
    /** The roster is known, so the "Viewing profile" control shows. */
    val available: Boolean get() = profiles.isNotEmpty()

    /**
     * The profile whose shots the range shows, or `null` for everyone: nobody's filtered without a
     * roster or with [ViewingProfile.AllProfiles]; a pinned profile that left the roster falls back
     * to the active one.
     */
    val filterProfileId: String?
        get() {
            val choice = viewing
            return when {
                !available || choice is ViewingProfile.AllProfiles -> null
                choice is ViewingProfile.Pinned && profiles.any { it.id == choice.profileId } -> choice.profileId
                else -> activeProfileId
            }
        }

    /** The active profile's name, for the "Active" choice's label. */
    val activeProfileName: String? get() = profiles.firstOrNull { it.id == activeProfileId }?.name

    /**
     * The quick settings' choices, in order: the active profile ("Active · Ann"), each profile, and
     * everyone; none without a roster (the control hides).
     */
    val choices: List<RangeProfileChoice>
        get() {
            if (!available) return emptyList()
            val pinned = (viewing as? ViewingProfile.Pinned)?.profileId?.takeIf { id -> profiles.any { it.id == id } }
            val following =
                viewing is ViewingProfile.FollowActive || (viewing is ViewingProfile.Pinned && pinned == null)
            return listOf(
                RangeProfileChoice(
                    ViewingProfile.FollowActive,
                    activeProfileName?.let { "Active · $it" } ?: "Active",
                    following,
                ),
            ) +
                profiles.map { RangeProfileChoice(ViewingProfile.Pinned(it.id), it.name, pinned == it.id) } +
                RangeProfileChoice(ViewingProfile.AllProfiles, "All profiles", viewing is ViewingProfile.AllProfiles)
        }

    /** Whether a shot filed under [shotProfileId] shows (the Session screen's rule, `isShotOfProfile`). */
    fun shows(shotProfileId: String?): Boolean = filterProfileId?.let { isShotOfProfile(shotProfileId, it) } ?: true

    companion object {
        /** The state for the device's [viewing] choice and the Pi's [roster] (ignored until loaded). */
        fun of(
            viewing: ViewingProfile,
            roster: ProfilesState,
        ): RangeProfileState =
            RangeProfileState(
                viewing = viewing,
                profiles = if (roster.loaded) roster.profiles else emptyList(),
                activeProfileId = roster.activeProfileId,
            )
    }
}

/** One "Viewing profile" choice in the range quick settings (plan F8f). */
data class RangeProfileChoice(
    val profile: ViewingProfile,
    val label: String,
    val selected: Boolean,
)
