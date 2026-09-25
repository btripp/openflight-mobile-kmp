// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/**
 * Test tags / accessibility identifiers for the session screens' destructive-action states and
 * the other plan R8f additions, shared with iOS.
 */
object SessionActionTestTags {
    /** The confirmation dialog's destructive button and its Cancel. */
    const val CONFIRM = "session.action.confirm"
    const val CANCEL = "session.action.cancel"

    /** The outcome panel, by phase. */
    const val PENDING = "session.action.pending"
    const val DONE = "session.action.done"
    const val FAILED = "session.action.failed"
    const val RETRY = "session.action.retry"
    const val DISMISS = "session.action.dismiss"

    /** "Not connected — showing the last session received." */
    const val STALE_NOTE = "session.staleNote"
}
