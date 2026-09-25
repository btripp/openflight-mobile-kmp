// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema v1 → v2 (plan F3), additive only: bags, bag clubs and activities are new tables; shots
 * gain the user's `starred`/`note`; sessions gain `source` (every existing row is the phone's own,
 * `LOCAL`), `owner_name`, `title`, `include_in_stats` (on) and `note`.
 *
 * Written by hand: each statement is the `createSql` Room exported to `schemas/.../2.json`, so the
 * migrated file matches a freshly created v2 one (`ShotHistoryMigrationTest` checks it).
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            V1_TO_V2.forEach { connection.execSQL(it) }
        }
    }

/** Every schema step, in order; `buildShotHistoryDatabase` installs them all. */
val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)

private val V1_TO_V2 =
    listOf(
        // sessions: every v1 row was recorded by this phone.
        "ALTER TABLE `sessions` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'LOCAL'",
        "ALTER TABLE `sessions` ADD COLUMN `owner_name` TEXT",
        "ALTER TABLE `sessions` ADD COLUMN `title` TEXT",
        "ALTER TABLE `sessions` ADD COLUMN `include_in_stats` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `sessions` ADD COLUMN `note` TEXT",
        // shots: the user's own marks.
        "ALTER TABLE `shots` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `shots` ADD COLUMN `note` TEXT",
        // bags: `is_active` is 1 or NULL, so the unique index allows one active bag (BagEntity).
        "CREATE TABLE IF NOT EXISTS `bags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `is_active` INTEGER, " +
            "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bags_one_active` ON `bags` (`is_active`)",
        "CREATE TABLE IF NOT EXISTS `bag_clubs` (`id` TEXT NOT NULL, `bag_id` TEXT NOT NULL, `club` TEXT NOT NULL, " +
            "`make` TEXT, `model` TEXT, `loft_deg` REAL, `sort_order` INTEGER NOT NULL, `note` TEXT, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`bag_id`) REFERENCES `bags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bag_clubs_bag_id_club` ON `bag_clubs` (`bag_id`, `club`)",
        "CREATE TABLE IF NOT EXISTS `activities` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
            "`started_at` INTEGER NOT NULL, `ended_at` INTEGER, `title` TEXT NOT NULL, `headline` TEXT NOT NULL, " +
            "`players_json` TEXT NOT NULL, `result_json` TEXT NOT NULL, `session_id` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_activities_session_id` ON `activities` (`session_id`)",
        "CREATE INDEX IF NOT EXISTS `index_activities_started_at` ON `activities` (`started_at`)",
    )
