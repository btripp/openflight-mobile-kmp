// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** Test tags / accessibility identifiers for the session history screens, shared with iOS (plan R8h). */
object SessionHistoryTestTags {
    /** The live Session screen's entry into the history. */
    const val OPEN = "session.history.open"
    const val LIST = "session.history.list"
    const val EMPTY = "session.history.empty"
    const val NOT_PERSISTENT = "session.history.notPersistent"
    const val CLEAR_ALL = "session.history.clearAll"
    const val CLEAR_ALL_CONFIRM = "session.history.clearAll.confirm"
    const val CLEAR_ALL_CANCEL = "session.history.clearAll.cancel"
    const val DONE = "session.history.done"
    const val DETAIL_TITLE = "session.history.detail.title"
    const val DETAIL_EXPORT = "session.history.detail.export"
    const val DETAIL_BACK = "session.history.detail.back"

    /** One session row, by its id. */
    fun session(id: String): String = "session.history.session.$id"
}
