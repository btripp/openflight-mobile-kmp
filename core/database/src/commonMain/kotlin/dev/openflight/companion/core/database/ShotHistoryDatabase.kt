// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

/**
 * The phone's persistent shot history (plan R8h): sessions and the shots filed under them, plus
 * (schema v2, plan F3) bags, their clubs and finished activities.
 *
 * Schema versions are exported to `core/database/schemas/` and committed. A new version needs a
 * `Migration` (or an `AutoMigration`) in [MIGRATIONS] plus a case in `ShotHistoryMigrationTest`;
 * never destructive fallback, since this is the player's only copy of past sessions.
 */
@Database(
    entities = [
        SessionEntity::class,
        ShotEntity::class,
        BagEntity::class,
        BagClubEntity::class,
        ActivityEntity::class,
    ],
    version = ShotHistoryDatabase.VERSION,
)
@ConstructedBy(ShotHistoryDatabaseConstructor::class)
abstract class ShotHistoryDatabase : RoomDatabase() {
    abstract fun shotHistoryDao(): ShotHistoryDao

    abstract fun bagDao(): BagDao

    abstract fun activityDao(): ActivityDao

    companion object {
        const val VERSION: Int = 2

        /** The file name under the platform's app-private directory. */
        const val FILE_NAME: String = "shot_history.db"
    }
}

/** Room's compiler generates the `actual` for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object ShotHistoryDatabaseConstructor : RoomDatabaseConstructor<ShotHistoryDatabase> {
    override fun initialize(): ShotHistoryDatabase
}

/**
 * Finishes a platform builder (`shotHistoryDatabaseBuilder(...)` in androidMain/iosMain, or
 * [inMemoryShotHistoryDatabaseBuilder]) the same way everywhere: the bundled SQLite driver, so the
 * SQLite version and behaviour are the same on Android and iOS, every schema [MIGRATIONS] step, and
 * queries off the main thread.
 */
fun RoomDatabase.Builder<ShotHistoryDatabase>.buildShotHistoryDatabase(
    queryContext: CoroutineContext = Dispatchers.IO,
): ShotHistoryDatabase =
    setDriver(BundledSQLiteDriver())
        .apply { MIGRATIONS.forEach { addMigrations(it) } }
        .setQueryCoroutineContext(queryContext)
        .build()

/** A database that lives only as long as the process: tests, and the fallback when the file can't open. */
expect fun inMemoryShotHistoryDatabaseBuilder(): RoomDatabase.Builder<ShotHistoryDatabase>
