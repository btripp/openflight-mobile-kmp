// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

/** Test tags / accessibility identifiers for the shot detail sheet, shared with iOS. */
object ShotDetailTestTags {
    const val SHEET = "shotDetail.sheet"
    const val TITLE = "shotDetail.title"
    const val SHAPE = "shotDetail.shape"
    const val FLIGHT = "shotDetail.flight"
    const val VIEW_PICKER = "shotDetail.viewPicker"
    const val NOTES = "shotDetail.notes"
    const val VIEW_ON_RANGE = "shotDetail.viewOnRange"
    const val CLOSE = "shotDetail.close"

    /** One metric cell, e.g. `shotDetail.metric.Carry`. */
    fun metric(label: String): String = "shotDetail.metric.$label"
}
