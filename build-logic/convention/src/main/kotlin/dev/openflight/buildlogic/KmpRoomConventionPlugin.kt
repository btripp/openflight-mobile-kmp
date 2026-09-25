// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import androidx.room3.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/**
 * `openflight.kmp.room`: an `openflight.kmp.library` that holds a Room 3 KMP database (plan R8h).
 *
 * - Applies `openflight.kmp.library` (Android + iosArm64 + iosSimulatorArm64), KSP and the
 *   `androidx.room3` Gradle plugin.
 * - Runs the Room compiler through KSP for every target: `kspAndroid`, `kspIosArm64`,
 *   `kspIosSimulatorArm64`. A new target needs its `ksp<Target>` line here, not in the module.
 * - Exports each schema version to `<module>/schemas/`. Those JSON files are source (they drive
 *   migrations and the migration tests), so they are committed.
 * - Enables `-Xexpect-actual-classes`: Room generates the `actual object` behind the database's
 *   `expect object ...Constructor`.
 * - commonMain gets `room3-runtime` (api: the database and DAO types are the module's API) and
 *   `sqlite-bundled` (the same SQLite on every platform through `BundledSQLiteDriver`). Android
 *   host tests load the host's SQLite binary (`configureBundledSqliteHostTests`, applied by
 *   `openflight.kmp.library`).
 * - iosTest gets `room3-testing`, and the simulator test task passes the schema directory as
 *   [SCHEMA_DIR_ENV] for `MigrationTestHelper`. Its file-based helper exists for native targets;
 *   the Android one needs an `Instrumentation` (device tests), so migrations are tested on the
 *   iOS simulator.
 */
class KmpRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("openflight.kmp.library")
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("androidx.room3")

            val schemaDirectory = "$projectDir/schemas"
            extensions.configure<RoomExtension> {
                schemaDirectory(schemaDirectory)
            }

            extensions.configure<KotlinMultiplatformExtension> {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
                sourceSets.getByName("commonMain").dependencies {
                    api(libs.library("androidx-room3-runtime"))
                    implementation(libs.library("androidx-sqlite-bundled"))
                }
                sourceSets.getByName("iosTest").dependencies {
                    implementation(libs.library("androidx-room3-testing"))
                }
            }

            // The simulator runs on this Mac's file system, so the test reads the committed schemas
            // in place. simctl forwards `SIMCTL_CHILD_*` variables to the test process.
            tasks.withType<KotlinNativeSimulatorTest>().configureEach {
                environment(SIMCTL_CHILD_PREFIX + SCHEMA_DIR_ENV, schemaDirectory)
            }

            dependencies {
                KSP_CONFIGURATIONS.forEach { configuration ->
                    add(configuration, libs.library("androidx-room3-compiler"))
                }
            }
        }
    }

    private companion object {
        /** Where migration tests find the exported schemas (`ShotHistoryMigrationTest`). */
        const val SCHEMA_DIR_ENV = "ROOM_SCHEMA_DIR"
        const val SIMCTL_CHILD_PREFIX = "SIMCTL_CHILD_"

        val KSP_CONFIGURATIONS = listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64")
    }
}
