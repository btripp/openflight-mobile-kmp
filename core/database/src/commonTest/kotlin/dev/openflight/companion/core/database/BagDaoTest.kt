// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** [BagDao] against a real in-memory Room database (schema v2, plan F3). */
class BagDaoTest {
    private lateinit var database: ShotHistoryDatabase
    private lateinit var dao: BagDao

    @BeforeTest
    fun setUp() {
        database = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
        dao = database.bagDao()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun creatingAnActiveBagLeavesItTheOnlyActiveOne() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver"), makeActive = true)
            dao.createBag(bag("b", 2L), clubs("b", "driver"), makeActive = true)
            dao.createBag(bag("c", 3L), clubs("c", "driver"), makeActive = false)

            assertThat(activeIds()).containsExactly("b")
        }

    @Test
    fun setActiveMovesTheActiveFlagAndIgnoresAnUnknownBag() =
        runTest {
            dao.createBag(bag("a", 1L), emptyList(), makeActive = true)
            dao.createBag(bag("b", 2L), emptyList(), makeActive = false)

            dao.setActive("b")
            assertThat(activeIds()).containsExactly("b")

            dao.setActive("missing")
            assertThat(activeIds()).containsExactly("b")
        }

    @Test
    fun seedingHappensOnlyIntoAnEmptyTable() =
        runTest {
            assertThat(dao.seedIfEmpty(bag("seed", 1L), clubs("seed", "driver", "pw"))).isTrue()
            assertThat(dao.seedIfEmpty(bag("again", 2L), clubs("again", "driver"))).isFalse()

            assertThat(dao.observeBags().first().map { it.id }).containsExactly("seed")
            assertThat(activeIds()).containsExactly("seed")
            assertThat(dao.clubsOf("seed").map { it.club }).containsExactly("driver", "pw")
        }

    @Test
    fun theLastBagCannotBeDeleted() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver"), makeActive = true)

            assertThat(dao.deleteBag("a")).isFalse()
            assertThat(dao.bagCount()).isEqualTo(1)
        }

    @Test
    fun deletingTheActiveBagRemovesItsClubsAndActivatesTheOldestLeft() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver"), makeActive = false)
            dao.createBag(bag("b", 2L), clubs("b", "driver"), makeActive = false)
            dao.createBag(bag("c", 3L), clubs("c", "driver", "pw"), makeActive = true)

            assertThat(dao.deleteBag("c")).isTrue()

            assertThat(dao.observeBags().first().map { it.id }).containsExactly("a", "b")
            assertThat(activeIds()).containsExactly("a")
            assertThat(dao.observeClubs().first().map { it.bagId }).containsExactly("a", "b")
            assertThat(dao.deleteBag("missing")).isFalse()
        }

    @Test
    fun upsertClubAppendsANewClubAndUpdatesAnExistingOneInPlace() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver", "7-iron"), makeActive = true)

            val added = dao.upsertClub(BagClubEntity(id = "new", bagId = "a", club = "pw", sortOrder = 0))
            val updated =
                dao.upsertClub(
                    BagClubEntity(
                        id = "ignored",
                        bagId = "a",
                        club = "driver",
                        make = "Acme",
                        loftDeg = 10.5,
                        sortOrder = 0,
                    ),
                )

            assertThat(added).isEqualTo("new")
            assertThat(updated).isEqualTo("a-driver")
            val stored = dao.clubsOf("a")
            assertThat(stored.map { it.club }).containsExactly("driver", "7-iron", "pw")
            assertThat(stored.first().make).isEqualTo("Acme")
            assertThat(stored.first().loftDeg).isEqualTo(10.5)
        }

    @Test
    fun reorderPutsTheListedClubsFirstAndKeepsTheRestInOrder() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver", "3-wood", "7-iron", "pw"), makeActive = true)

            dao.reorder("a", listOf("a-pw", "a-3-wood", "not-in-bag", "a-pw"))

            assertThat(dao.clubsOf("a").map { it.club }).containsExactly("pw", "3-wood", "driver", "7-iron")
            assertThat(dao.clubsOf("a").map { it.sortOrder }).containsExactly(0, 1, 2, 3)
        }

    @Test
    fun renameAndRemoveClub() =
        runTest {
            dao.createBag(bag("a", 1L), clubs("a", "driver", "pw"), makeActive = true)

            dao.renameBag("a", "Winter bag")
            dao.removeClub("a-driver")

            assertThat(
                dao
                    .observeBags()
                    .first()
                    .single()
                    .name,
            ).isEqualTo("Winter bag")
            assertThat(dao.clubsOf("a").map { it.club }).containsExactly("pw")
        }

    private suspend fun activeIds(): List<String> =
        dao
            .observeBags()
            .first()
            .filter { it.isActive == true }
            .map { it.id }

    private companion object {
        fun bag(
            id: String,
            createdAt: Long,
        ) = BagEntity(id = id, name = "Bag $id", createdAtEpochMillis = createdAt)

        fun clubs(
            bagId: String,
            vararg wireValues: String,
        ) = wireValues.mapIndexed { index, club ->
            BagClubEntity(id = "$bagId-$club", bagId = bagId, club = club, sortOrder = index)
        }
    }
}
