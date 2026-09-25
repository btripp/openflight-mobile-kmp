// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The migration harness (plan R8h). Schema v1 is the baseline: this proves the committed
 * `schemas/.../1.json` matches the compiled entities and that rows written with the v1 schema read
 * back through today's DAO. A new version adds a `Migration` plus a case here:
 * `helper.createDatabase(n)`, write rows with SQL, `helper.runMigrationsAndValidate(n + 1,
 * listOf(MIGRATION_n_n+1))`, then read them back through the DAO.
 *
 * iOS only: room3-testing's file-based helper is native/JVM; on Android it needs an
 * `Instrumentation`. `openflight.kmp.room` passes the schema directory in [SCHEMA_DIR_ENV].
 */
class ShotHistoryMigrationTest {
    private val path = NSTemporaryDirectory() + "shot-history-migration-test.db"
    private lateinit var helper: MigrationTestHelper

    @BeforeTest
    fun setUp() {
        deleteDatabaseFiles()
        val schemaDirectory = NSProcessInfo.processInfo.environment[SCHEMA_DIR_ENV] as? String
        assertThat(schemaDirectory, name = SCHEMA_DIR_ENV).isNotNull()
        helper =
            MigrationTestHelper(
                schemaDirectoryPath = schemaDirectory!!,
                fileName = path,
                driver = BundledSQLiteDriver(),
                databaseClass = ShotHistoryDatabase::class,
                databaseFactory = ShotHistoryDatabaseConstructor::initialize,
            )
    }

    @AfterTest
    fun tearDown() {
        helper.finished()
        deleteDatabaseFiles()
    }

    @Test
    fun version1SchemaMatchesTheEntitiesAndItsRowsReadBack() =
        runTest {
            helper.createDatabase(1).apply {
                execSQL(
                    "INSERT INTO sessions (id, started_at, host, transport) " +
                        "VALUES ('s1', 1000, 'pi.local:8080', 'WIFI')",
                )
                execSQL(
                    "INSERT INTO shots (session_id, shot_number, timestamp, club, ball_speed_mph, " +
                        "has_detail, raw_json) VALUES ('s1', 1, '2026-09-14T10:00:00', 'driver', 148.2, 1, '{}')",
                )
                close()
            }

            // Validates the v1 file against what Room compiled, with no migration needed.
            helper.runMigrationsAndValidate(1, emptyList()).close()

            val database =
                Room
                    .databaseBuilder<ShotHistoryDatabase>(
                        name = path,
                        factory = ShotHistoryDatabaseConstructor::initialize,
                    ).buildShotHistoryDatabase()
            try {
                val dao = database.shotHistoryDao()
                val shots = dao.observeShots("s1").first()
                assertThat(shots.map { it.timestamp }).containsExactly("2026-09-14T10:00:00")
                assertThat(shots.single().ballSpeedMph).isEqualTo(148.2)
                assertThat(
                    dao
                        .observeSessions()
                        .first()
                        .single()
                        .shotCount,
                ).isEqualTo(1)
            } finally {
                database.close()
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun deleteDatabaseFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            NSFileManager.defaultManager.removeItemAtPath(path + suffix, error = null)
        }
    }

    private companion object {
        const val SCHEMA_DIR_ENV = "ROOM_SCHEMA_DIR"
    }
}
