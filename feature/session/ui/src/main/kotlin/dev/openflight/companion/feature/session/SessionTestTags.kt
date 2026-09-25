// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** Test tags for the Android session screen. */
object SessionTestTags {
    const val DONE = "session.done"
    const val EXPORT = "session.export"
    const val CLEAR = "session.clear"
    const val EDIT_DISABLED_REASON = "session.edit.disabledReason"
    const val SIMULATE = "session.simulate"
    const val SOURCE = "session.source"
    const val STATS = "session.stats"
    const val EMPTY = "session.empty"
    const val ALL_TAB = "session.tab.all"
    const val DISPERSION = "session.dispersion"
    const val SELECTED = "session.selected"
    const val SELECTED_CLOSE = "session.selected.close"
    const val SELECTED_DELETE = "session.selected.delete"
    const val SPREAD = "session.spread"
    const val BAD_READ_NOTE = "session.selected.badRead"

    /** A club tab, by its wire value (e.g. `"7-iron"`). */
    fun tab(club: String): String = "session.tab.$club"

    /** A stat tile, by its label (e.g. "Shots"). */
    fun stat(label: String): String = "session.stat.$label"

    /** A shot row, by its [SessionShotRow.id]. */
    fun shot(id: String): String = "session.shot.$id"
}
