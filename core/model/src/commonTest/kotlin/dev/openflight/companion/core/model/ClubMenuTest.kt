// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class ClubMenuTest {
    @Test
    fun yourClubsKeepTheBagOrder() {
        val bag = listOf(GolfClub.PITCHING_WEDGE, GolfClub.DRIVER, GolfClub.IRON_7)

        val menu = clubMenu(bag, selected = GolfClub.DRIVER)

        assertThat(menu.yourClubs).containsExactly(GolfClub.PITCHING_WEDGE, GolfClub.DRIVER, GolfClub.IRON_7)
    }

    @Test
    fun otherClubsAreTheRestInCatalogOrder() {
        val bag = listOf(GolfClub.IRON_7, GolfClub.DRIVER)

        val menu = clubMenu(bag, selected = GolfClub.DRIVER)

        assertThat(menu.otherClubs).isEqualTo(GolfClub.entries.filterNot { it in bag })
        assertThat(menu.hasOtherClubs).isTrue()
    }

    @Test
    fun aSelectedClubMissingFromTheBagIsAddedToYourClubs() {
        val bag = listOf(GolfClub.DRIVER, GolfClub.IRON_7)

        val menu = clubMenu(bag, selected = GolfClub.IRON_4)

        assertThat(menu.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.IRON_4)
        assertThat(GolfClub.IRON_4 in menu.otherClubs).isFalse()
    }

    @Test
    fun noBagListsAllTwentyClubsFlat() {
        val menu = clubMenu(bag = null, selected = GolfClub.IRON_7)

        assertThat(menu.yourClubs).isEqualTo(GolfClub.entries.toList())
        assertThat(menu.otherClubs).isEmpty()
        assertThat(menu.hasOtherClubs).isFalse()
        assertThat(menu).isEqualTo(ClubMenu.ALL)
    }

    @Test
    fun anEmptyBagListsAllTwentyClubsFlat() {
        val menu = clubMenu(bag = emptyList(), selected = GolfClub.LOB_WEDGE)

        assertThat(menu.yourClubs).isEqualTo(GolfClub.entries.toList())
        assertThat(menu.otherClubs).isEmpty()
    }

    @Test
    fun aRepeatedClubIsListedOnce() {
        val menu = clubMenu(listOf(GolfClub.DRIVER, GolfClub.DRIVER), selected = GolfClub.DRIVER)

        assertThat(menu.yourClubs).containsExactly(GolfClub.DRIVER)
    }

    @Test
    fun theListsNeverOverlapAndTogetherHoldEveryClub() {
        val bags =
            listOf(
                listOf(GolfClub.DRIVER),
                listOf(GolfClub.LOB_WEDGE, GolfClub.WOOD_3, GolfClub.IRON_5),
                GolfClub.entries.toList(),
                GolfClub.entries.reversed(),
            )
        for (bag in bags) {
            for (selected in GolfClub.entries) {
                val menu = clubMenu(bag, selected)

                assertThat(menu.yourClubs.intersect(menu.otherClubs.toSet())).isEmpty()
                assertThat(menu.yourClubs + menu.otherClubs).containsExactlyInAnyOrder(*GolfClub.entries.toTypedArray())
                assertThat(selected in menu.yourClubs).isTrue()
            }
        }
    }
}
