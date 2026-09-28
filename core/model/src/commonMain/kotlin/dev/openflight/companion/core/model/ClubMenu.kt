// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * What a "next club" picker lists (issue #15): the player's clubs first, then the rest of the 20
 * [GolfClub]s tucked under "All clubs". Built by [clubMenu]; the Dashboard and the Range both use it.
 *
 * The two lists never overlap, and together they hold every [GolfClub] once.
 *
 * @property yourClubs the active bag's clubs in bag order, plus the selected club when the bag
 *   lacks it (so the current club always shows). Every club when there is no bag.
 * @property otherClubs every other club, in [GolfClub.entries] order. Empty when there is no bag.
 */
data class ClubMenu(
    val yourClubs: List<GolfClub>,
    val otherClubs: List<GolfClub>,
) {
    /** Whether the picker needs its "All clubs" section. */
    val hasOtherClubs: Boolean get() = otherClubs.isNotEmpty()

    companion object {
        /** No bag: all 20 clubs, flat. */
        val ALL: ClubMenu = ClubMenu(yourClubs = GolfClub.entries.toList(), otherClubs = emptyList())

        /** The label of the section holding [otherClubs]. */
        const val ALL_CLUBS_LABEL: String = "All clubs"
    }
}

/**
 * The picker for the active bag's clubs [bag] (in bag order; `null` without a bag) with [selected]
 * as the current club. A club the bag lacks but the Pi has selected (e.g. from its kiosk) is added
 * to the end of [ClubMenu.yourClubs]. No bag, or an empty one, lists all 20 clubs flat.
 */
fun clubMenu(
    bag: List<GolfClub>?,
    selected: GolfClub,
): ClubMenu {
    val clubs = bag.orEmpty().distinct()
    if (clubs.isEmpty()) return ClubMenu.ALL
    val yourClubs = if (selected in clubs) clubs else clubs + selected
    return ClubMenu(yourClubs = yourClubs, otherClubs = GolfClub.entries.filterNot { it in yourClubs })
}
