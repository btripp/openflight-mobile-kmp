// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.BagClubEntity
import dev.openflight.companion.core.database.BagDao
import dev.openflight.companion.core.database.BagEntity
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** [BagRepository] over the Room [BagDao] in the shared [HistoryDatabase] (plan F3). */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@Suppress("TooManyFunctions") // The repository surface.
internal class DefaultBagRepository(
    private val database: HistoryDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { Uuid.random().toString() },
    private val log: (String) -> Unit = ::println,
) : BagRepository {
    override fun bags(): Flow<List<Bag>> =
        database
            .observe { it.bagDao().observeBagsWithClubs() }
            .map { rows -> rows.map { it.bag.toBag(it.clubs) } }

    override fun activeBag(): Flow<Bag?> =
        bags().map { bags -> bags.firstOrNull { it.isActive } }.distinctUntilChanged()

    override suspend fun createBag(
        name: String,
        clubs: List<GolfClub>,
        makeActive: Boolean,
    ): String? =
        write("create bag") { dao ->
            val bag = newBag(name)
            dao.createBag(bag, clubEntities(bag.id, clubs), makeActive)
            bag.id
        }

    override suspend fun setActive(bagId: String) {
        write("set active bag") { it.setActive(bagId) }
    }

    override suspend fun upsertClub(
        bagId: String,
        club: GolfClub,
        make: String?,
        model: String?,
        loftDeg: Double?,
        note: String?,
    ): String? =
        write("save club") { dao ->
            dao.upsertClub(
                BagClubEntity(
                    id = newId(),
                    bagId = bagId,
                    club = club.wireValue,
                    make = make,
                    model = model,
                    loftDeg = loftDeg,
                    // The DAO appends a new club at the end, or keeps an existing club's place.
                    sortOrder = 0,
                    note = note,
                ),
            )
        }

    override suspend fun removeClub(clubId: String) {
        write("remove club") { it.removeClub(clubId) }
    }

    override suspend fun reorder(
        bagId: String,
        clubIds: List<String>,
    ) {
        write("reorder clubs") { it.reorder(bagId, clubIds) }
    }

    override suspend fun renameBag(
        bagId: String,
        name: String,
    ) {
        write("rename bag") { it.renameBag(bagId, name) }
    }

    override suspend fun deleteBag(bagId: String): Boolean = write("delete bag") { it.deleteBag(bagId) } ?: false

    override suspend fun seedDefaultBagIfEmpty(): Boolean =
        write("seed default bag") { dao ->
            val bag = newBag(BagRepository.DEFAULT_BAG_NAME)
            dao.seedIfEmpty(bag, clubEntities(bag.id, BagRepository.DEFAULT_CLUBS))
        } ?: false

    private fun newBag(name: String) = BagEntity(id = newId(), name = name, createdAtEpochMillis = now())

    private fun clubEntities(
        bagId: String,
        clubs: List<GolfClub>,
    ): List<BagClubEntity> =
        clubs.distinct().mapIndexed { index, club ->
            BagClubEntity(id = newId(), bagId = bagId, club = club.wireValue, sortOrder = index)
        }

    /** Runs [block] on the DAO; `null` without a database or when it fails (logged). */
    @Suppress("TooGenericExceptionCaught") // A failed bag write is logged, never a crash.
    private suspend fun <T> write(
        what: String,
        block: suspend (BagDao) -> T,
    ): T? {
        val dao = database.get()?.bagDao() ?: return null
        return try {
            block(dao)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("Bag $what failed: ${error.message ?: error}")
            null
        }
    }

    private companion object {
        /** A club row whose wire value this app doesn't know (a newer app wrote it) is left out. */
        fun BagEntity.toBag(clubs: List<BagClubEntity>) =
            Bag(
                id = id,
                name = name,
                isActive = isActive == true,
                createdAtEpochMillis = createdAtEpochMillis,
                clubs =
                    clubs.sortedWith(compareBy({ it.sortOrder }, { it.id })).mapNotNull { row ->
                        GolfClub.fromWireValue(row.club)?.let { club ->
                            BagClub(
                                id = row.id,
                                club = club,
                                make = row.make,
                                model = row.model,
                                loftDeg = row.loftDeg,
                                note = row.note,
                            )
                        }
                    },
            )
    }
}
