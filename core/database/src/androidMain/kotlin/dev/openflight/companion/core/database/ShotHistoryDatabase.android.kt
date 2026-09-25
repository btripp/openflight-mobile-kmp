// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

/** The on-disk history in the app's private databases directory (`Context.getDatabasePath`). */
fun shotHistoryDatabaseBuilder(context: Context): RoomDatabase.Builder<ShotHistoryDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<ShotHistoryDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(ShotHistoryDatabase.FILE_NAME).absolutePath,
    )
}

actual fun inMemoryShotHistoryDatabaseBuilder(): RoomDatabase.Builder<ShotHistoryDatabase> =
    Room.inMemoryDatabaseBuilder<ShotHistoryDatabase>()
