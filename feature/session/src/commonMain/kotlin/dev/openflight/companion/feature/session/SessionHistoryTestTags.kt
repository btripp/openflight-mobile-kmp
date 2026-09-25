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
    const val DONE = "session.history.done"
    const val DETAIL_TITLE = "session.history.detail.title"
    const val DETAIL_EXPORT = "session.history.detail.export"
    const val DETAIL_BACK = "session.history.detail.back"

    /** How a stored session was recorded ("Wi-Fi · raspberrypi.local:8080"), plan R8f. */
    const val DETAIL_SOURCE = "session.history.detail.source"

    /** The "Current" badge on the session new shots are filed under. */
    const val CURRENT = "session.history.current"

    /** The detail's profile filter: "All profiles", then one chip per profile id. */
    const val PROFILE_ALL = "session.history.profile.all"

    fun profile(id: String): String = "session.history.profile.$id"

    /** One session row, by its id. */
    fun session(id: String): String = "session.history.session.$id"
}
