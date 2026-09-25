// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.Activity
import dev.openflight.companion.core.data.ActivityRepository
import dev.openflight.companion.core.data.Bag
import dev.openflight.companion.core.data.BagClub
import dev.openflight.companion.core.data.BagRepository
import dev.openflight.companion.core.data.FinalShotStream
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [BagRepository] for ViewModel tests (plan F3), following the real rules: one active
 * bag, the last bag can't be deleted, a club once per bag. Ids are `bag-N` and `club-N`.
 */
@Suppress("TooManyFunctions") // Mirrors the BagRepository surface.
class FakeBagRepository : BagRepository {
    val state = MutableStateFlow<List<Bag>>(emptyList())
    private var nextId = 0

    override fun bags(): Flow<List<Bag>> = state

    override fun activeBag(): Flow<Bag?> = state.map { bags -> bags.firstOrNull { it.isActive } }

    override suspend fun createBag(
        name: String,
        clubs: List<GolfClub>,
        makeActive: Boolean,
    ): String {
        val bag =
            Bag(
                id = "bag-${++nextId}",
                name = name,
                isActive = makeActive,
                createdAtEpochMillis = nextId.toLong(),
                clubs = clubs.distinct().map { BagClub(id = "club-${++nextId}", club = it) },
            )
        state.update { bags -> (if (makeActive) bags.map { it.copy(isActive = false) } else bags) + bag }
        return bag.id
    }

    override suspend fun setActive(bagId: String) {
        if (state.value.none { it.id == bagId }) return
        state.update { bags -> bags.map { it.copy(isActive = it.id == bagId) } }
    }

    override suspend fun upsertClub(
        bagId: String,
        club: GolfClub,
        make: String?,
        model: String?,
        loftDeg: Double?,
        note: String?,
    ): String? {
        val bag = state.value.firstOrNull { it.id == bagId } ?: return null
        val existing = bag.clubs.firstOrNull { it.club == club }
        val id = existing?.id ?: "club-${++nextId}"
        val updated = BagClub(id = id, club = club, make = make, model = model, loftDeg = loftDeg, note = note)
        val clubs = if (existing != null) bag.clubs.map { if (it.id == id) updated else it } else bag.clubs + updated
        updateBag(bagId) { it.copy(clubs = clubs) }
        return id
    }

    override suspend fun removeClub(clubId: String) {
        state.update { bags -> bags.map { bag -> bag.copy(clubs = bag.clubs.filterNot { it.id == clubId }) } }
    }

    override suspend fun reorder(
        bagId: String,
        clubIds: List<String>,
    ) = updateBag(bagId) { bag ->
        val listed = clubIds.distinct().mapNotNull { id -> bag.clubs.firstOrNull { it.id == id } }
        bag.copy(clubs = listed + bag.clubs.filterNot { it in listed })
    }

    override suspend fun renameBag(
        bagId: String,
        name: String,
    ) = updateBag(bagId) { it.copy(name = name) }

    override suspend fun deleteBag(bagId: String): Boolean {
        val bags = state.value
        val bag = bags.firstOrNull { it.id == bagId }?.takeIf { bags.size > 1 } ?: return false
        val rest = bags - bag
        state.value = if (bag.isActive) rest.mapIndexed { index, left -> left.copy(isActive = index == 0) } else rest
        return true
    }

    override suspend fun seedDefaultBagIfEmpty(): Boolean {
        if (state.value.isNotEmpty()) return false
        createBag(BagRepository.DEFAULT_BAG_NAME, BagRepository.DEFAULT_CLUBS, makeActive = true)
        return true
    }

    private fun updateBag(
        bagId: String,
        change: (Bag) -> Bag,
    ) = state.update { bags -> bags.map { if (it.id == bagId) change(it) else it } }
}

/** An in-memory [ActivityRepository] for ViewModel tests (plan F3): newest first, replace by id. */
class FakeActivityRepository : ActivityRepository {
    val state = MutableStateFlow<List<Activity>>(emptyList())

    override fun activities(type: String?): Flow<List<Activity>> =
        state.map { activities ->
            activities
                .filter { type == null || it.type == type }
                .sortedWith(compareByDescending<Activity> { it.startedAtEpochMillis }.thenByDescending { it.id })
        }

    override suspend fun record(activity: Activity) {
        state.update { activities -> activities.filterNot { it.id == activity.id } + activity }
    }

    override suspend fun delete(id: String) {
        state.update { activities -> activities.filterNot { it.id == id } }
    }
}

/**
 * A [FinalShotStream] a test drives directly: [emitFinal] and [emitFirstSighting] push a shot to
 * the collectors (none are replayed to a later collector).
 */
class FakeFinalShotStream : FinalShotStream {
    private val finals = MutableSharedFlow<ShotEvent>(extraBufferCapacity = BUFFER)
    private val sightings = MutableSharedFlow<ShotEvent>(extraBufferCapacity = BUFFER)

    override fun finalShots(): Flow<ShotEvent> = finals

    override fun firstSightings(): Flow<ShotEvent> = sightings

    suspend fun emitFinal(shot: ShotEvent) = finals.emit(shot)

    suspend fun emitFirstSighting(shot: ShotEvent) = sightings.emit(shot)

    private companion object {
        const val BUFFER = 64
    }
}
