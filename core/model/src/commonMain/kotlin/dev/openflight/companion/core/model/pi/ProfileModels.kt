// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One profile (`Profile.to_dict`, backend profiles.py:67): a named context shots are attributed
 * to, a person or a place. It replaced the old single player name.
 *
 * @property createdAt ISO-8601 UTC, seconds precision, `Z`-suffixed.
 * @property settings an open dict the server persists without interpreting it. Round-trip it and
 *   never narrow it: narrowing would silently drop keys another client wrote.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String = "",
    val settings: JsonObject = JsonObject(emptyMap()),
)

/**
 * The `profiles` event (profiles.py:127 `snapshot`): the server's whole roster and selection,
 * broadcast after every mutation, including one it refuses (a refusal has no error event).
 *
 * @property activeProfileId `""` when nothing is selected yet.
 */
@Serializable
data class ProfilesSnapshot(
    val profiles: List<Profile>,
    @SerialName("active_profile_id") val activeProfileId: String = "",
)

/**
 * The roster as the app holds it: the last [ProfilesSnapshot], applied verbatim.
 *
 * @property loaded `false` until the first snapshot arrives, so a screen can tell "not asked yet"
 *   from "no profiles".
 */
data class ProfilesState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String = "",
    val loaded: Boolean = false,
) {
    /** The profile whose id is [activeProfileId], or `null`. */
    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }

    /** Whether another profile can be added ([ProfileRules.MAX_PROFILES]). */
    val canAdd: Boolean get() = profiles.size < ProfileRules.MAX_PROFILES
}

/** The outcome of [ProfileRules.checkName]. */
sealed interface ProfileNameCheck {
    /** Usable; [name] is trimmed. */
    data class Valid(
        val name: String,
    ) : ProfileNameCheck

    /** Empty or whitespace only: the server would refuse it silently. */
    data object Blank : ProfileNameCheck

    /** Longer than [ProfileRules.MAX_NAME_LENGTH] after trimming: the server would truncate it. */
    data object TooLong : ProfileNameCheck
}

/**
 * The server's profile rules (profiles.py:29-51). None are on the wire and a refused mutation
 * comes back as an unchanged roster, so the client enforces them to turn a silent no-op into
 * something the user can see (Expo `ProfileNameForm.tsx`, `ProfilePicker.tsx`).
 */
object ProfileRules {
    /** `MAX_PROFILES`: the server refuses a thirteenth profile. */
    const val MAX_PROFILES: Int = 12

    /** `MAX_NAME_LENGTH`: the server truncates longer names. */
    const val MAX_NAME_LENGTH: Int = 40

    /** Trims [raw] and checks it against the server's name rules. */
    fun checkName(raw: String): ProfileNameCheck {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> ProfileNameCheck.Blank
            trimmed.length > MAX_NAME_LENGTH -> ProfileNameCheck.TooLong
            else -> ProfileNameCheck.Valid(trimmed)
        }
    }
}
