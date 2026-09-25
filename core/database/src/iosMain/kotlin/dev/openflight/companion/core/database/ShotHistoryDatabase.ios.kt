// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** The on-disk history in the app's Documents directory, next to the settings DataStore. */
fun shotHistoryDatabaseBuilder(): RoomDatabase.Builder<ShotHistoryDatabase> =
    Room.databaseBuilder<ShotHistoryDatabase>(
        name = "${documentDirectoryPath()}/${ShotHistoryDatabase.FILE_NAME}",
    )

actual fun inMemoryShotHistoryDatabaseBuilder(): RoomDatabase.Builder<ShotHistoryDatabase> =
    Room.inMemoryDatabaseBuilder<ShotHistoryDatabase>()

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectoryPath(): String {
    val url =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    return requireNotNull(url?.path) { "No document directory" }
}
