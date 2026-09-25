// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** [DefaultBagRepository] over a real in-memory database (plan F3). */
class BagRepositoryTest {
    private class Harness(
        scope: TestScope,
        openDatabase: () -> ShotHistoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase,
    ) {
        private var clock = 0L
        private var ids = 0
        val repository =
            DefaultBagRepository(
                database = HistoryDatabase(openDatabase, scope.backgroundScope, fallbackDatabase, log = {}),
                now = { ++clock },
                newId = { "id-${++ids}" },
                log = {},
            )
    }

    private fun runBagTest(
        openDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        body(Harness(this, openDatabase, fallbackDatabase))
    }

    // A10: the default bag is 14 clubs, each one a GolfClub the Pi knows.
    @Test
    fun theDefaultBagIsFourteenKnownClubs() {
        val clubs = BagRepository.DEFAULT_CLUBS
        assertThat(clubs).hasSize(14)
        assertThat(clubs.distinct()).hasSize(14)
        clubs.forEach { club -> assertThat(GolfClub.fromWireValue(club.wireValue)).isEqualTo(club) }
        assertThat(clubs.map { it.wireValue }).containsExactly(
            "driver",
            "3-wood",
            "5-wood",
            "3-hybrid",
            "4-iron",
            "5-iron",
            "6-iron",
            "7-iron",
            "8-iron",
            "9-iron",
            "pw",
            "gw",
            "sw",
            "lw",
        )
    }

    @Test
    fun seedingStoresTheActiveDefaultBagOnceOnly() =
        runBagTest { h ->
            assertThat(h.repository.seedDefaultBagIfEmpty()).isTrue()
            assertThat(h.repository.seedDefaultBagIfEmpty()).isFalse()

            val bags = h.repository.bags().first()
            assertThat(bags).hasSize(1)
            val bag = bags.single()
            assertThat(bag.name).isEqualTo(BagRepository.DEFAULT_BAG_NAME)
            assertThat(bag.isActive).isTrue()
            assertThat(bag.clubs.map { it.club }).isEqualTo(BagRepository.DEFAULT_CLUBS)
            assertThat(
                h.repository
                    .activeBag()
                    .first()
                    ?.id,
            ).isEqualTo(bag.id)
        }

    @Test
    fun onlyOneBagIsEverActive() =
        runBagTest { h ->
            val first = h.repository.createBag("Summer", listOf(GolfClub.DRIVER), makeActive = true)!!
            val second = h.repository.createBag("Winter", listOf(GolfClub.IRON_7), makeActive = true)!!
            h.repository.createBag("Spare", listOf(GolfClub.PITCHING_WEDGE))

            h.repository.activeBag().test {
                assertThat(awaitItem()?.id).isEqualTo(second)

                h.repository.setActive(first)
                assertThat(awaitItem()?.id).isEqualTo(first)
            }
            assertThat(
                h.repository
                    .bags()
                    .first()
                    .filter { it.isActive }
                    .map { it.id },
            ).containsExactly(first)
        }

    @Test
    fun reorderIsStable() =
        runBagTest { h ->
            val bagId =
                h.repository.createBag(
                    "Bag",
                    listOf(GolfClub.DRIVER, GolfClub.WOOD_3, GolfClub.IRON_7, GolfClub.PITCHING_WEDGE),
                )!!
            val clubs =
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
            val ids = clubs.map { it.id }
            val driver = ids[0]
            val wood = ids[1]
            val iron = ids[2]
            val wedge = ids[3]

            h.repository.reorder(bagId, listOf(wedge, iron))
            val once =
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
                    .map { it.id }
            assertThat(once).containsExactly(wedge, iron, driver, wood)

            // The same order again changes nothing.
            h.repository.reorder(bagId, once)
            assertThat(
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
                    .map { it.id },
            ).isEqualTo(once)
        }

    @Test
    fun theLastBagCannotBeDeleted() =
        runBagTest { h ->
            h.repository.seedDefaultBagIfEmpty()
            val only =
                h.repository
                    .bags()
                    .first()
                    .single()

            assertThat(h.repository.deleteBag(only.id)).isFalse()
            assertThat(
                h.repository
                    .bags()
                    .first()
                    .map { it.id },
            ).containsExactly(only.id)
        }

    @Test
    fun deletingTheActiveBagHandsTheFlagToTheOldestLeft() =
        runBagTest { h ->
            val oldest = h.repository.createBag("Old", listOf(GolfClub.DRIVER))!!
            h.repository.createBag("Middle", listOf(GolfClub.DRIVER))
            val active = h.repository.createBag("New", listOf(GolfClub.DRIVER), makeActive = true)!!

            assertThat(h.repository.deleteBag(active)).isTrue()

            assertThat(
                h.repository
                    .activeBag()
                    .first()
                    ?.id,
            ).isEqualTo(oldest)
        }

    @Test
    fun upsertClubAddsOnceThenUpdatesTheSameClub() =
        runBagTest { h ->
            val bagId = h.repository.createBag("Bag", listOf(GolfClub.DRIVER))!!

            val added = h.repository.upsertClub(bagId, GolfClub.GAP_WEDGE, loftDeg = 50.0)
            val again = h.repository.upsertClub(bagId, GolfClub.GAP_WEDGE, make = "Acme", loftDeg = 52.0)

            assertThat(again).isEqualTo(added)
            val clubs =
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
            assertThat(clubs.map { it.club }).containsExactly(GolfClub.DRIVER, GolfClub.GAP_WEDGE)
            assertThat(clubs.last().make).isEqualTo("Acme")
            assertThat(clubs.last().loftDeg).isEqualTo(52.0)
        }

    @Test
    fun aRepeatedClubInANewBagIsKeptOnce() =
        runBagTest { h ->
            h.repository.createBag("Bag", listOf(GolfClub.DRIVER, GolfClub.DRIVER, GolfClub.IRON_7))

            assertThat(
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
                    .map { it.club },
            ).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7)
        }

    @Test
    fun renameAndRemoveClub() =
        runBagTest { h ->
            val bagId = h.repository.createBag("Bag", listOf(GolfClub.DRIVER, GolfClub.IRON_7))!!
            val driver =
                h.repository
                    .bags()
                    .first()
                    .single()
                    .clubs
                    .first()

            h.repository.renameBag(bagId, "Travel bag")
            h.repository.removeClub(driver.id)

            val bag =
                h.repository
                    .bags()
                    .first()
                    .single()
            assertThat(bag.name).isEqualTo("Travel bag")
            assertThat(bag.clubs.map { it.club }).containsExactly(GolfClub.IRON_7)
        }

    @Test
    fun withNoDatabaseReadsAreEmptyAndWritesDoNothing() =
        runBagTest(openDatabase = { error("disk I/O error") }, fallbackDatabase = { error("no memory") }) { h ->
            assertThat(h.repository.seedDefaultBagIfEmpty()).isFalse()
            assertThat(h.repository.createBag("Bag", listOf(GolfClub.DRIVER))).isNull()
            assertThat(h.repository.deleteBag("any")).isFalse()
            assertThat(h.repository.bags().first()).isEmpty()
            assertThat(h.repository.activeBag().first()).isNull()
        }

    @Test
    fun theFallbackDatabaseStillHoldsABag() =
        runBagTest(openDatabase = { error("disk I/O error") }) { h ->
            assertThat(h.repository.seedDefaultBagIfEmpty()).isTrue()
            assertThat(h.repository.activeBag().first()).isNotNull()
        }

    private companion object {
        fun inMemoryDatabase(): ShotHistoryDatabase = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
    }
}
