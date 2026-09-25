// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.ProfileNameCheck
import dev.openflight.companion.core.model.pi.ProfileRules
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The profile picker on the live dashboard (plan R8f; Expo `ProfilePicker.tsx`,
 * `ProfileNameForm.tsx`, `feat/profile-selection`).
 *
 * It shows what the Pi last reported rather than the last tap: the Pi answers every mutation with
 * the whole roster, including one it refuses, so that reply is the only truth. Over Bluetooth
 * with a schema v2 Pi only selecting is possible; adding, renaming and removing need Wi-Fi
 * ([PiFeatureAvailability.forProfileEdits]).
 *
 * @property activeName the active profile's name, or `null` before the roster arrives.
 * @property rows the roster in the Pi's order, the active one marked.
 * @property selection whether the picker can open and switch profiles; the sheet closes when this
 *   turns unavailable (a change made while disconnected would go nowhere).
 * @property edits whether add, rename and remove can run.
 * @property notice a message about the last mutation, e.g. that the Pi kept a profile it refused
 *   to remove.
 */
data class ProfilePickerState(
    val loaded: Boolean = false,
    val activeName: String? = null,
    val rows: List<ProfileRow> = emptyList(),
    val sheet: ProfileSheet = ProfileSheet.Closed,
    val selection: PiFeatureAvailability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED),
    val edits: PiFeatureAvailability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED),
    val canAdd: Boolean = true,
    val notice: String? = null,
) {
    /** The trigger's text: the active profile's name, or "Not set". */
    val label: String get() = activeName ?: NOT_SET

    /** Add is offered only on Wi-Fi and below [ProfileRules.MAX_PROFILES]. */
    val addEnabled: Boolean get() = edits.isAvailable && canAdd

    companion object {
        const val NOT_SET: String = "Not set"
        const val TITLE: String = "Select profile"
        val AT_CAPACITY_TEXT: String =
            "This Pi holds ${ProfileRules.MAX_PROFILES} profiles, the most it accepts. Remove one to add another."
        const val REFUSED_REMOVAL: String =
            "The Pi kept this profile. It won't remove the active profile, the last one, " +
                "or one with shots in the session."
        const val REFUSAL_WAIT_MILLIS: Long = 3_000
    }
}

/**
 * One profile in the sheet.
 *
 * @property removeBlockedReason why the Pi would refuse to remove it (active, last, or it has
 *   session rows), or `null` when Remove can be offered.
 */
data class ProfileRow(
    val id: String,
    val name: String,
    val active: Boolean,
    val removeBlockedReason: String?,
)

/** What the profile sheet shows. */
sealed interface ProfileSheet {
    data object Closed : ProfileSheet

    /** The roster, to pick from. */
    data object List : ProfileSheet

    /** The add form with its [draft] and the rule it breaks, if any. */
    data class Adding(
        val draft: String,
        val error: String? = null,
    ) : ProfileSheet

    data class Renaming(
        val profileId: String,
        val draft: String,
        val error: String? = null,
    ) : ProfileSheet

    /** "Remove [name]?": removing is destructive, so it's confirmed first. */
    data class ConfirmingRemoval(
        val profileId: String,
        val name: String,
    ) : ProfileSheet
}

/** Profile picker intents, sent through [DashboardViewModel.onEvent] like every dashboard event. */
sealed interface ProfilePickerEvent : DashboardEvent {
    data object Open : ProfilePickerEvent

    data object Close : ProfilePickerEvent

    data class Select(
        val profileId: String,
    ) : ProfilePickerEvent

    data object StartAdd : ProfilePickerEvent

    data class StartRename(
        val profileId: String,
    ) : ProfilePickerEvent

    /** A keystroke in the name field; capped at [ProfileRules.MAX_NAME_LENGTH] like Expo's `maxLength`. */
    data class NameEdited(
        val text: String,
    ) : ProfilePickerEvent

    /** The form's Add/Save: checks the name rules, then sends. */
    data object SubmitName : ProfilePickerEvent

    /** Back from a form or a removal confirmation to the list. */
    data object CancelForm : ProfilePickerEvent

    data class Remove(
        val profileId: String,
    ) : ProfilePickerEvent

    data object ConfirmRemove : ProfilePickerEvent

    data object DismissNotice : ProfilePickerEvent
}

/**
 * The picker's state machine, owned by [DashboardViewModel] and run in its [scope]. Commands go
 * straight to [PiSessionRepository]; the roster that comes back is what the sheet shows.
 */
@Suppress("TooManyFunctions") // One small handler per picker intent.
internal class ProfilePicker(
    private val piSession: PiSessionRepository,
    private val scope: CoroutineScope,
) {
    private val sheet = MutableStateFlow<ProfileSheet>(ProfileSheet.Closed)
    private val notice = MutableStateFlow<String?>(null)
    private var removalWatch: Job? = null

    private val availability =
        combine(piSession.linkState, piSession.bluetoothSchemaV2) { link, bluetoothV2 ->
            PiFeatureAvailability.forProfileSelection(link, bluetoothV2) to PiFeatureAvailability.forProfileEdits(link)
        }

    val state: Flow<ProfilePickerState> =
        combine(piSession.profiles, piSession.sessionShots, availability, sheet, notice) {
            profiles,
            shots,
            (selection, edits),
            sheet,
            notice,
            ->
            ProfilePickerState(
                loaded = profiles.loaded,
                activeName = profiles.activeProfile?.name,
                rows = rows(profiles, shots),
                sheet = sheet,
                selection = selection,
                edits = edits,
                canAdd = profiles.canAdd,
                notice = notice,
            )
        }

    init {
        scope.launch {
            availability.collect { (selection, edits) ->
                when {
                    // Closes with the connection and stays closed when it returns (Expo).
                    !selection.isAvailable -> sheet.value = ProfileSheet.Closed

                    !edits.isAvailable && sheet.value.isForm() -> sheet.value = ProfileSheet.List
                }
            }
        }
    }

    fun onEvent(event: ProfilePickerEvent) {
        when (event) {
            ProfilePickerEvent.Open -> open()
            ProfilePickerEvent.Close -> sheet.value = ProfileSheet.Closed
            is ProfilePickerEvent.Select -> select(event.profileId)
            ProfilePickerEvent.StartAdd -> startAdd()
            is ProfilePickerEvent.StartRename -> startRename(event.profileId)
            is ProfilePickerEvent.NameEdited -> editName(event.text.take(ProfileRules.MAX_NAME_LENGTH))
            ProfilePickerEvent.SubmitName -> submitName()
            ProfilePickerEvent.CancelForm -> if (sheet.value != ProfileSheet.Closed) sheet.value = ProfileSheet.List
            is ProfilePickerEvent.Remove -> askRemoval(event.profileId)
            ProfilePickerEvent.ConfirmRemove -> confirmRemoval()
            ProfilePickerEvent.DismissNotice -> notice.value = null
        }
    }

    private fun currentSelection(): PiFeatureAvailability =
        PiFeatureAvailability.forProfileSelection(piSession.linkState.value, piSession.bluetoothSchemaV2.value)

    private fun editsAvailable(): Boolean = PiFeatureAvailability.forProfileEdits(piSession.linkState.value).isAvailable

    private fun open() {
        if (currentSelection().isAvailable) {
            notice.value = null
            sheet.value = ProfileSheet.List
        }
    }

    /** Picking closes the sheet; the Pi's roster reply carries the switch. */
    private fun select(profileId: String) {
        sheet.value = ProfileSheet.Closed
        if (profileId == piSession.profiles.value.activeProfileId) return
        send { piSession.setActiveProfile(profileId) }
    }

    private fun startAdd() {
        if (editsAvailable() && piSession.profiles.value.canAdd) sheet.value = ProfileSheet.Adding(draft = "")
    }

    private fun startRename(profileId: String) {
        val profile =
            piSession.profiles.value.profiles
                .firstOrNull { it.id == profileId } ?: return
        if (editsAvailable()) sheet.value = ProfileSheet.Renaming(profileId, profile.name)
    }

    private fun editName(text: String) {
        sheet.value =
            when (val current = sheet.value) {
                is ProfileSheet.Adding -> current.copy(draft = text, error = null)
                is ProfileSheet.Renaming -> current.copy(draft = text, error = null)
                else -> current
            }
    }

    private fun submitName() {
        val current = sheet.value
        val draft =
            when (current) {
                is ProfileSheet.Adding -> current.draft
                is ProfileSheet.Renaming -> current.draft
                else -> null
            } ?: return
        when (val check = ProfileRules.checkName(draft)) {
            is ProfileNameCheck.Valid -> sendName(current, check.name)
            ProfileNameCheck.Blank -> showFormError(BLANK_NAME)
            ProfileNameCheck.TooLong -> showFormError(NAME_TOO_LONG)
        }
    }

    private fun sendName(
        form: ProfileSheet,
        name: String,
    ) {
        sheet.value = ProfileSheet.List
        if (form is ProfileSheet.Adding) {
            // The Pi makes the new profile active, so no set_active_profile follows.
            send { piSession.addProfile(name) }
        } else if (form is ProfileSheet.Renaming) {
            val unchanged =
                piSession.profiles.value.profiles
                    .any { it.id == form.profileId && it.name == name }
            if (!unchanged) send { piSession.renameProfile(form.profileId, name) }
        }
    }

    private fun showFormError(error: String) {
        sheet.value =
            when (val current = sheet.value) {
                is ProfileSheet.Adding -> current.copy(error = error)
                is ProfileSheet.Renaming -> current.copy(error = error)
                else -> current
            }
    }

    private fun askRemoval(profileId: String) {
        val profile =
            piSession.profiles.value.profiles
                .firstOrNull { it.id == profileId } ?: return
        if (editsAvailable()) sheet.value = ProfileSheet.ConfirmingRemoval(profileId, profile.name)
    }

    /**
     * Sends `remove_profile`. The Pi refuses silently (an unchanged roster), and an unchanged
     * roster isn't a new value to observe, so a profile still listed after
     * [ProfilePickerState.REFUSAL_WAIT_MILLIS] reads as "the Pi kept it".
     */
    private fun confirmRemoval() {
        val removal = sheet.value as? ProfileSheet.ConfirmingRemoval ?: return
        sheet.value = ProfileSheet.List
        notice.value = null
        removalWatch?.cancel()
        removalWatch =
            scope.launch {
                if (!trySend { piSession.removeProfile(removal.profileId) }) return@launch
                val removed =
                    withTimeoutOrNull(ProfilePickerState.REFUSAL_WAIT_MILLIS) {
                        piSession.profiles.first { roster -> roster.profiles.none { it.id == removal.profileId } }
                    }
                if (removed ==
                    null
                ) {
                    notice.value = "“${removal.name}” is unchanged. ${ProfilePickerState.REFUSED_REMOVAL}"
                }
            }
    }

    private fun send(command: suspend () -> Unit) {
        scope.launch { trySend(command) }
    }

    /** Runs [command]; a failure (a rule, "Not connected", a BLE refusal) becomes the [notice]. */
    @Suppress("TooGenericExceptionCaught") // Every failure is shown the same way.
    private suspend fun trySend(command: suspend () -> Unit): Boolean =
        try {
            command()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            notice.value = failure.message ?: COMMAND_FAILED
            false
        }

    private fun ProfileSheet.isForm(): Boolean =
        this is ProfileSheet.Adding || this is ProfileSheet.Renaming || this is ProfileSheet.ConfirmingRemoval

    companion object {
        const val BLANK_NAME: String = "Enter a name for the profile."
        val NAME_TOO_LONG: String = "Profile names can be at most ${ProfileRules.MAX_NAME_LENGTH} characters."
        const val COMMAND_FAILED: String = "The Pi didn't accept that."
        const val ACTIVE_PROFILE: String = "Active profile"
        const val LAST_PROFILE: String = "The only profile"
        const val HAS_SHOTS: String = "Has shots in the session"

        /** The server's removal rules, checked up front so Remove isn't offered where it would be refused. */
        fun rows(
            profiles: ProfilesState,
            sessionShots: kotlin.collections.List<ShotDetail>,
        ): kotlin.collections.List<ProfileRow> {
            val withShots = sessionShots.mapNotNullTo(mutableSetOf()) { it.profileId }
            return profiles.profiles.map { profile ->
                val active = profile.id == profiles.activeProfileId
                ProfileRow(
                    id = profile.id,
                    name = profile.name,
                    active = active,
                    removeBlockedReason =
                        when {
                            active -> ACTIVE_PROFILE
                            profiles.profiles.size <= 1 -> LAST_PROFILE
                            profile.id in withShots -> HAS_SHOTS
                            else -> null
                        },
                )
            }
        }
    }
}
