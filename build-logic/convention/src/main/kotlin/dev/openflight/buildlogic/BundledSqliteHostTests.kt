// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Lets Android **host** (JVM) tests open a Room database through `BundledSQLiteDriver` (plan R8h).
 *
 * The Android variant of `androidx.sqlite:sqlite-bundled` only ships `libsqliteJni.so` for Android
 * ABIs, so on the host `System.loadLibrary("sqliteJni")` fails. Its loader
 * (`NativeLibraryLoader.android.kt`, sqlite 2.7.1) first honours the system properties
 * `androidx.sqlite.driver.bundled.path` / `.name`, so this extracts the host's binary from the
 * JVM variant (`sqlite-bundled-jvm`, `natives/<os>_<arch>/`) and points `testAndroidHostTest` at
 * it. Tests that never open a database are unaffected. iOS tests link SQLite statically and need
 * nothing.
 */
internal fun Project.configureBundledSqliteHostTests() {
    val host = hostNativeDir() ?: return
    val natives = configurations.create("bundledSqliteHostNatives") { isTransitive = false }
    dependencies.add(natives.name, libs.library("androidx-sqlite-bundled-jvm"))

    val outputDir = layout.buildDirectory.dir("bundled-sqlite-natives")
    val extract =
        tasks.register<Sync>("extractBundledSqliteHostNatives") {
            from({ natives.map { zipTree(it) } }) {
                include("natives/${host.dir}/${host.fileName}")
                eachFile { path = name }
                includeEmptyDirs = false
            }
            into(outputDir)
        }

    tasks.withType<Test>().configureEach {
        if (name != HOST_TEST_TASK) return@configureEach
        dependsOn(extract)
        val libraryDir = outputDir.map { it.asFile.absolutePath }
        jvmArgumentProviders.add { listOf("-Dandroidx.sqlite.driver.bundled.path=${libraryDir.get()}") }
        systemProperty("androidx.sqlite.driver.bundled.name", host.fileName)
    }
}

private const val HOST_TEST_TASK = "testAndroidHostTest"

private data class HostNative(
    val dir: String,
    val fileName: String,
)

/** The `natives/` entry of `sqlite-bundled-jvm` for this build machine, or `null` if it ships none. */
private fun hostNativeDir(): HostNative? {
    val os = System.getProperty("os.name").lowercase()
    val arch =
        when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64" -> "x64"
            else -> return null
        }
    return when {
        os.contains("mac") -> HostNative("osx_$arch", "libsqliteJni.dylib")
        os.contains("linux") -> HostNative("linux_$arch", "libsqliteJni.so")
        os.contains("windows") -> HostNative("windows_$arch", "sqliteJni.dll")
        else -> null
    }
}
