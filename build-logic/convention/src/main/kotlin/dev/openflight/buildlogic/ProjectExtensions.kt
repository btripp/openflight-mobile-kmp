// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/** Root package shared by every module. */
internal const val BASE_NAMESPACE = "dev.openflight.companion"

/** Bytecode level for Android/JVM compilations. AGP 9 runs on JDK 17+. */
internal val OPENFLIGHT_JVM_TARGET = JvmTarget.JVM_17

/**
 * Derives an Android namespace from the Gradle path, so `:core:model` becomes
 * `dev.openflight.companion.core.model`. Modules can still override `namespace`.
 */
internal fun Project.defaultNamespace(): String {
    val suffix =
        path.removePrefix(":").split(':').joinToString(".") { segment ->
            segment.replace('-', '_').lowercase()
        }
    return "$BASE_NAMESPACE.$suffix"
}
