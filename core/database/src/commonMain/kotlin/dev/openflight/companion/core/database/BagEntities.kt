// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One golf bag (schema v2, plan F3): a named set of clubs. At most one bag is active.
 *
 * "At most one active" is enforced by the database, not only by [BagDao.setActive]: [isActive] is
 * `1` for the active bag and `NULL` for every other one, and the unique index on it lets only one
 * row hold `1`, since SQLite treats every `NULL` as distinct in a unique index. That is the same
 * guarantee as a partial unique index `ON bags(is_active) WHERE is_active = 1`, which Room can't
 * declare: Room's schema check reads every index back and would reject a migrated database that
 * holds an index its entities don't declare. Never store `0`: two `0`s would collide.
 *
 * @property isActive `true` (stored as `1`) for the active bag, `null` for the others.
 */
@Entity(
    tableName = "bags",
    indices = [Index(value = ["is_active"], unique = true, name = "index_bags_one_active")],
)
data class BagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

/**
 * One club in a bag (schema v2). A bag holds each club once (the unique `(bag_id, club)` index).
 *
 * @property club the club's wire value (`GolfClub.wireValue`, e.g. `"7-iron"`).
 * @property sortOrder the club's place in the bag, ascending.
 */
@Entity(
    tableName = "bag_clubs",
    foreignKeys = [
        ForeignKey(
            entity = BagEntity::class,
            parentColumns = ["id"],
            childColumns = ["bag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bag_id", "club"], unique = true)],
)
data class BagClubEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "bag_id") val bagId: String,
    val club: String,
    val make: String? = null,
    val model: String? = null,
    @ColumnInfo(name = "loft_deg") val loftDeg: Double? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val note: String? = null,
)

/**
 * One finished game or activity (schema v2): what the Activities history shows.
 *
 * @property type the activity kind (`feature:games` owns the names).
 * @property headline the card's big text, e.g. `"4.2 yd"`.
 * @property playersJson/resultJson opaque JSON the owning feature writes and reads.
 * @property sessionId the history session the activity's shots were filed under, if it still exists.
 */
@Entity(
    tableName = "activities",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["session_id"]), Index(value = ["started_at"])],
)
data class ActivityEntity(
    @PrimaryKey val id: String,
    val type: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at") val endedAtEpochMillis: Long? = null,
    val title: String,
    val headline: String,
    @ColumnInfo(name = "players_json") val playersJson: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
)
