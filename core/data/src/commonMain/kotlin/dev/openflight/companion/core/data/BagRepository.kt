// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.Flow

/**
 * The player's golf bags (plan F3): named sets of clubs, one of them active. The single source of
 * truth for "which clubs do I carry", stored with the shot history.
 *
 * Rules:
 * - At most one bag is active; [setActive] moves the flag, and the database itself refuses a
 *   second active bag.
 * - The last bag can't be deleted ([deleteBag] returns `false`).
 * - A bag holds each club once: [upsertClub] with a club the bag already holds updates it.
 *
 * Nothing here throws: without a database (see [ShotHistoryRepository.isPersistent]) reads are
 * empty, writes do nothing and return `null`/`false`, and a failed write is logged.
 */
interface BagRepository {
    /** Every bag with its clubs in bag order, oldest bag first. */
    fun bags(): Flow<List<Bag>>

    /** The active bag, or `null` when there is none (before [seedDefaultBagIfEmpty]). */
    fun activeBag(): Flow<Bag?>

    /**
     * Creates a bag holding [clubs] in that order (a repeated club is kept once).
     *
     * @return the new bag's id, or `null` when it couldn't be stored.
     */
    suspend fun createBag(
        name: String,
        clubs: List<GolfClub>,
        makeActive: Boolean = false,
    ): String?

    /** Makes [bagId] the only active bag. Unknown ids are ignored. */
    suspend fun setActive(bagId: String)

    /**
     * Adds [club] to the end of bag [bagId], or updates the details of the bag's [club] if it
     * already holds one (keeping its place).
     *
     * @return the club's id in the bag, or `null` when it couldn't be stored.
     */
    @Suppress("LongParameterList") // The club plus its optional details.
    suspend fun upsertClub(
        bagId: String,
        club: GolfClub,
        make: String? = null,
        model: String? = null,
        loftDeg: Double? = null,
        note: String? = null,
    ): String?

    /** Removes the club [clubId] ([BagClub.id]) from its bag. */
    suspend fun removeClub(clubId: String)

    /**
     * Puts the clubs [clubIds] first in bag [bagId], in that order; the bag's other clubs keep
     * their relative order after them. Ids not in the bag are ignored.
     */
    suspend fun reorder(
        bagId: String,
        clubIds: List<String>,
    )

    suspend fun renameBag(
        bagId: String,
        name: String,
    )

    /**
     * Deletes bag [bagId] and its clubs. Refused for the last bag. When the active bag goes, the
     * oldest remaining one becomes active.
     *
     * @return whether it was deleted.
     */
    suspend fun deleteBag(bagId: String): Boolean

    /**
     * Stores [DEFAULT_BAG_NAME] with [DEFAULT_CLUBS], active, when there is no bag at all.
     *
     * @return whether it was stored.
     */
    suspend fun seedDefaultBagIfEmpty(): Boolean

    companion object {
        const val DEFAULT_BAG_NAME: String = "My Bag"

        /**
         * The default set: 14 clubs, the Rules of Golf limit (Rule 4.1b). Driver, 3- and 5-wood,
         * a 3-hybrid (there is no 4-hybrid [GolfClub]), 4- to 9-iron and the four wedges.
         */
        val DEFAULT_CLUBS: List<GolfClub> =
            listOf(
                GolfClub.DRIVER,
                GolfClub.WOOD_3,
                GolfClub.WOOD_5,
                GolfClub.HYBRID_3,
                GolfClub.IRON_4,
                GolfClub.IRON_5,
                GolfClub.IRON_6,
                GolfClub.IRON_7,
                GolfClub.IRON_8,
                GolfClub.IRON_9,
                GolfClub.PITCHING_WEDGE,
                GolfClub.GAP_WEDGE,
                GolfClub.SAND_WEDGE,
                GolfClub.LOB_WEDGE,
            )
    }
}

/**
 * One bag.
 *
 * @property clubs in bag order.
 */
data class Bag(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val createdAtEpochMillis: Long,
    val clubs: List<BagClub>,
)

/**
 * One club in a bag, with the player's optional details about it.
 *
 * @property id the club's id in its bag, for [BagRepository.removeClub] and [BagRepository.reorder].
 */
data class BagClub(
    val id: String,
    val club: GolfClub,
    val make: String? = null,
    val model: String? = null,
    val loftDeg: Double? = null,
    val note: String? = null,
)
