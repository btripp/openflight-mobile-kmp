// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/**
 * A destructive action on the session screens (plan 9.2, the Expo policy: "every destructive action
 * gets a confirmation plus observable pending, success and error states").
 */
sealed interface SessionAction {
    /**
     * Deleting one shot by its row id ([SessionShotRow.id]).
     *
     * @property shotNumber the row's `#n`, to name the shot in the prompt and the outcome.
     */
    data class DeleteShot(
        val id: String,
        val shotNumber: Int,
        val clubName: String,
    ) : SessionAction

    /**
     * Clearing the live session: the Pi's session for one profile ([profileId], with its
     * [profileName] when known), or the phone's own list when [profileId] is `null`.
     */
    data class ClearSession(
        val profileId: String?,
        val profileName: String?,
    ) : SessionAction

    /** Deleting every stored session from the phone (plan R8h, a local action). */
    data object ClearAllHistory : SessionAction
}

/**
 * Where a [SessionAction] is: [Idle], [Confirming] (nothing sent yet), [Pending] (sent; every
 * destructive control is disabled so nothing is sent twice), [Done] or [Failed]. [Done] and
 * [Failed] stay until dismissed or replaced by the next action, so the outcome can't be missed.
 */
sealed interface SessionActionState {
    /** The action this state is about, or `null` when [Idle]. */
    val action: SessionAction?

    data object Idle : SessionActionState {
        override val action: SessionAction? = null
    }

    /** The confirmation prompt: [title], [message] and the destructive button's [confirmLabel]. */
    data class Confirming(
        override val action: SessionAction,
        val title: String,
        val message: String,
        val confirmLabel: String,
    ) : SessionActionState

    /** Sent, waiting for the outcome; [message] says what's happening ("Deleting shot #3…"). */
    data class Pending(
        override val action: SessionAction,
        val message: String,
    ) : SessionActionState

    data class Done(
        override val action: SessionAction,
        val message: String,
    ) : SessionActionState

    /**
     * It failed or wasn't confirmed. [canRetry] is `false` while retrying can't work (e.g. the
     * Pi's link is down, or another profile is now active), and [message] then says why.
     */
    data class Failed(
        override val action: SessionAction,
        val title: String,
        val message: String,
        val canRetry: Boolean,
    ) : SessionActionState

    /** While [Pending], delete and clear controls are disabled. */
    val isBusy: Boolean get() = this is Pending
}

/** The prompts and outcomes of [SessionAction]s, shared so Android and iOS say the same thing. */
object SessionActionCopy {
    const val CONFIRM_DELETE: String = "Delete"
    const val CONFIRM_CLEAR: String = "Clear"
    const val CONFIRM_CLEAR_ALL: String = "Clear all"
    const val RETRY: String = "Try again"
    const val DISMISS: String = "OK"

    /** The Pi answered neither `session_state` nor `delete_shot_error` in time. */
    const val DELETE_NOT_CONFIRMED: String = "The Pi didn't confirm the delete. The list shows what it last reported."

    /** Shown instead of Retry while the Pi's link is down. */
    const val RECONNECT_TO_RETRY: String = "Reconnect to the Pi to try again."

    /** A Pi clear can only be retried for the profile it was asked for. */
    const val PROFILE_CHANGED: String = "The active profile changed, so this clear can't be retried."

    /** The phone's history store didn't reflect a local change in time. */
    const val STORAGE_DID_NOT_RESPOND: String = "The phone's history storage didn't respond."

    fun shotLabel(action: SessionAction.DeleteShot): String = "shot #${action.shotNumber} (${action.clubName})"

    fun confirmDelete(
        action: SessionAction.DeleteShot,
        onPi: Boolean,
    ): SessionActionState.Confirming =
        SessionActionState.Confirming(
            action = action,
            title = "Delete shot #${action.shotNumber}?",
            message =
                if (onPi) {
                    "This ${action.clubName} shot is removed from the Pi's session and from this phone."
                } else {
                    "This ${action.clubName} shot is removed from this phone."
                },
            confirmLabel = CONFIRM_DELETE,
        )

    fun confirmHistoryDelete(action: SessionAction.DeleteShot): SessionActionState.Confirming =
        SessionActionState.Confirming(
            action = action,
            title = "Delete shot #${action.shotNumber}?",
            message = "This ${action.clubName} shot is removed from this phone's history. The Pi isn't changed.",
            confirmLabel = CONFIRM_DELETE,
        )

    fun confirmClear(action: SessionAction.ClearSession): SessionActionState.Confirming {
        val profileId = action.profileId
        return SessionActionState.Confirming(
            action = action,
            title = if (profileId == null) "Clear session?" else "Clear ${possessive(action)} session?",
            message =
                if (profileId == null) {
                    "This session's list on this phone is emptied. Saved sessions stay in History."
                } else {
                    "${possessiveCapitalised(action)} shots are removed from the Pi's session and from this " +
                        "phone. Other profiles keep theirs."
                },
            confirmLabel = CONFIRM_CLEAR,
        )
    }

    val confirmClearAll: SessionActionState.Confirming =
        SessionActionState.Confirming(
            action = SessionAction.ClearAllHistory,
            title = "Clear all history?",
            message = "Every stored session is deleted from this phone. The Pi's session is not affected.",
            confirmLabel = CONFIRM_CLEAR_ALL,
        )

    fun pending(action: SessionAction): String =
        when (action) {
            is SessionAction.DeleteShot -> {
                "Deleting shot #${action.shotNumber}…"
            }

            is SessionAction.ClearSession -> {
                if (action.profileId ==
                    null
                ) {
                    "Clearing this phone's list…"
                } else {
                    "Clearing ${possessive(action)} session…"
                }
            }

            SessionAction.ClearAllHistory -> {
                "Clearing history…"
            }
        }

    fun done(action: SessionAction): String =
        when (action) {
            is SessionAction.DeleteShot -> {
                "Shot #${action.shotNumber} deleted."
            }

            is SessionAction.ClearSession -> {
                if (action.profileId ==
                    null
                ) {
                    "This phone's list is cleared."
                } else {
                    "${possessiveCapitalised(action)} session is cleared."
                }
            }

            SessionAction.ClearAllHistory -> {
                "History cleared."
            }
        }

    fun failedTitle(action: SessionAction): String =
        when (action) {
            is SessionAction.DeleteShot -> "Couldn't delete shot #${action.shotNumber}"

            // The server has no error reply for a clear: a failure means "not confirmed", not "not cleared".
            is SessionAction.ClearSession -> "Clear not confirmed"

            SessionAction.ClearAllHistory -> "Couldn't clear history"
        }

    private fun possessive(action: SessionAction.ClearSession): String =
        action.profileName?.takeIf { it.isNotBlank() }?.let { "$it's" } ?: "this profile's"

    private fun possessiveCapitalised(action: SessionAction.ClearSession): String =
        possessive(action).replaceFirstChar { it.uppercaseChar() }
}
