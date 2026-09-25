// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/** One row of [BagDao.observeBagsWithClubs]; [clubs] are unordered (sort by `sort_order`). */
data class BagWithClubs(
    @Embedded val bag: BagEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["bag_id"]) val clubs: List<BagClubEntity>,
)

/**
 * Bags and their clubs (schema v2, plan F3). Multi-step writes run in one transaction each, so the
 * "one active bag" and "never the last bag" rules hold under concurrent callers.
 */
@Dao
@Suppress("TooManyFunctions") // One function per query, as Room wants them.
abstract class BagDao {
    /** Every bag, oldest first. */
    @Query("SELECT * FROM bags ORDER BY created_at, id")
    abstract fun observeBags(): Flow<List<BagEntity>>

    /** Every bag with its clubs, oldest bag first: one consistent snapshot per change. */
    @Transaction
    @Query("SELECT * FROM bags ORDER BY created_at, id")
    abstract fun observeBagsWithClubs(): Flow<List<BagWithClubs>>

    /** Every bag's clubs, in bag order. */
    @Query("SELECT * FROM bag_clubs ORDER BY bag_id, sort_order, id")
    abstract fun observeClubs(): Flow<List<BagClubEntity>>

    /** Inserts [bag] with [clubs]; when [makeActive], it becomes the only active bag. */
    @Transaction
    open suspend fun createBag(
        bag: BagEntity,
        clubs: List<BagClubEntity>,
        makeActive: Boolean,
    ) {
        if (makeActive) deactivateAll()
        insertBag(bag.copy(isActive = if (makeActive) true else null))
        clubs.forEach { insertClub(it) }
    }

    /**
     * Inserts [bag] (active) with [clubs] only when there is no bag yet.
     *
     * @return whether it was inserted.
     */
    @Transaction
    open suspend fun seedIfEmpty(
        bag: BagEntity,
        clubs: List<BagClubEntity>,
    ): Boolean {
        if (bagCount() > 0) return false
        createBag(bag, clubs, makeActive = true)
        return true
    }

    /** Makes [bagId] the only active bag. A no-op (leaving the active bag alone) if it doesn't exist. */
    @Transaction
    open suspend fun setActive(bagId: String) {
        if (findBag(bagId) == null) return
        deactivateAll()
        activate(bagId)
    }

    /**
     * Deletes [bagId] and its clubs, unless it is the only bag. When it was the active bag, the
     * oldest remaining bag becomes active.
     *
     * @return whether it was deleted: `false` for the last bag or an unknown id.
     */
    @Transaction
    open suspend fun deleteBag(bagId: String): Boolean {
        val bag = findBag(bagId)?.takeIf { bagCount() > 1 } ?: return false
        deleteClubsOf(bagId)
        deleteBagRow(bagId)
        if (bag.isActive == true) oldestBagId()?.let { activate(it) }
        return true
    }

    /**
     * Adds [club] to its bag, or, when the bag already holds that club, updates the stored row's
     * details (keeping its id and place).
     *
     * @return the club row's id.
     */
    @Transaction
    open suspend fun upsertClub(club: BagClubEntity): String {
        val existing = findClub(club.bagId, club.club)
        if (existing != null) {
            updateClub(club.copy(id = existing.id, sortOrder = existing.sortOrder))
            return existing.id
        }
        insertClub(club.copy(sortOrder = (maxSortOrder(club.bagId) ?: -1) + 1))
        return club.id
    }

    /**
     * Puts [orderedIds] first, in that order; the bag's other clubs keep their relative order after
     * them. Ids not in the bag are ignored.
     */
    @Transaction
    open suspend fun reorder(
        bagId: String,
        orderedIds: List<String>,
    ) {
        val current = clubsOf(bagId)
        val byId = current.associateBy { it.id }
        val listed = orderedIds.distinct().mapNotNull { byId[it] }
        val rest = current.filterNot { club -> listed.any { it.id == club.id } }
        (listed + rest).forEachIndexed { index, club -> setSortOrder(club.id, index) }
    }

    @Query("UPDATE bags SET name = :name WHERE id = :bagId")
    abstract suspend fun renameBag(
        bagId: String,
        name: String,
    )

    @Query("DELETE FROM bag_clubs WHERE id = :clubId")
    abstract suspend fun removeClub(clubId: String)

    @Query("SELECT COUNT(*) FROM bags")
    abstract suspend fun bagCount(): Int

    @Query("SELECT * FROM bag_clubs WHERE bag_id = :bagId ORDER BY sort_order, id")
    abstract suspend fun clubsOf(bagId: String): List<BagClubEntity>

    @Insert
    protected abstract suspend fun insertBag(bag: BagEntity)

    @Insert
    protected abstract suspend fun insertClub(club: BagClubEntity)

    @Update
    protected abstract suspend fun updateClub(club: BagClubEntity)

    @Query("SELECT * FROM bags WHERE id = :bagId")
    protected abstract suspend fun findBag(bagId: String): BagEntity?

    @Query("SELECT * FROM bag_clubs WHERE bag_id = :bagId AND club = :club")
    protected abstract suspend fun findClub(
        bagId: String,
        club: String,
    ): BagClubEntity?

    @Query("SELECT MAX(sort_order) FROM bag_clubs WHERE bag_id = :bagId")
    protected abstract suspend fun maxSortOrder(bagId: String): Int?

    @Query("SELECT id FROM bags ORDER BY created_at, id LIMIT 1")
    protected abstract suspend fun oldestBagId(): String?

    @Query("UPDATE bags SET is_active = NULL WHERE is_active = 1")
    protected abstract suspend fun deactivateAll()

    @Query("UPDATE bags SET is_active = 1 WHERE id = :bagId")
    protected abstract suspend fun activate(bagId: String)

    @Query("UPDATE bag_clubs SET sort_order = :sortOrder WHERE id = :clubId")
    protected abstract suspend fun setSortOrder(
        clubId: String,
        sortOrder: Int,
    )

    // Explicit rather than relying on the foreign key's CASCADE, so it holds with foreign keys off.
    @Query("DELETE FROM bag_clubs WHERE bag_id = :bagId")
    protected abstract suspend fun deleteClubsOf(bagId: String)

    @Query("DELETE FROM bags WHERE id = :bagId")
    protected abstract suspend fun deleteBagRow(bagId: String)
}
