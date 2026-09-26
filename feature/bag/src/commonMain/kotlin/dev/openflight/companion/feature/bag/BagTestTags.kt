// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

/** Test tags (Android) and accessibility identifiers (iOS) for the bag screens (plan F5). */
object BagTestTags {
    const val LIST = "bag.list"
    const val BAG_NAME = "bag.name"
    const val EDIT_TOGGLE = "bag.edit"
    const val ADD_CLUB = "bag.addClub"
    const val OPEN_ANALYSIS = "bag.openAnalysis"
    const val ERROR = "bag.error"
    const val CONDITIONS_CARD = "bag.conditions"
    const val CONDITIONS_SUMMARY = "bag.conditions.summary"
    const val CONDITIONS_EDIT = "bag.conditions.edit"
    const val CONDITIONS_SAVE = "bag.conditions.save"
    const val CONDITIONS_ALTITUDE = "bag.conditions.altitude"
    const val CONDITIONS_TEMPERATURE = "bag.conditions.temperature"
    const val CONDITIONS_WIND_SPEED = "bag.conditions.windSpeed"
    const val CONDITIONS_WIND_FROM = "bag.conditions.windFrom"
    const val CONDITIONS_TARGET = "bag.conditions.target"
    const val CONDITIONS_ERROR = "bag.conditions.error"
    const val WIND_NOTE = "bag.conditions.windNote"
    const val DETAIL_PLACEHOLDER = "bag.detail.placeholder"

    const val ANALYSIS_DONE = "bag.analysis.done"
    const val ANALYSIS_LIST = "bag.analysis.list"
    const val ANALYSIS_EMPTY = "bag.analysis.empty"
    const val INSIGHT = "bag.analysis.insight"

    const val DETAIL = "bag.detail"
    const val DETAIL_DONE = "bag.detail.done"
    const val DETAIL_SUMMARY = "bag.detail.summary"
    const val DETAIL_EMPTY = "bag.detail.empty"
    const val HISTOGRAM = "bag.detail.histogram"
    const val DISPERSION = "bag.detail.dispersion"
    const val RECENT = "bag.detail.recent"
    const val ESTIMATED = "bag.estimated"

    /** A club row on My Bag, by its wire value (e.g. `"7-iron"`). */
    fun club(wireValue: String): String = "bag.club.$wireValue"

    /** The gap chip below a club's row. */
    fun gap(wireValue: String): String = "bag.gap.$wireValue"

    fun moveUp(wireValue: String): String = "bag.club.$wireValue.up"

    fun moveDown(wireValue: String): String = "bag.club.$wireValue.down"

    fun remove(wireValue: String): String = "bag.club.$wireValue.remove"

    /** An "add this club" option. */
    fun add(wireValue: String): String = "bag.add.$wireValue"

    /** A bag in the bag switcher. */
    fun bag(bagId: String): String = "bag.bag.$bagId"

    /** A bar on Club Analysis. */
    fun bar(wireValue: String): String = "bag.analysis.bar.$wireValue"

    /** A window option (e.g. "Lifetime") or metric option (e.g. "Carry"). */
    fun option(label: String): String = "bag.analysis.option.$label"

    fun surface(label: String): String = "bag.conditions.surface.$label"
}
