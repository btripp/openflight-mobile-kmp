// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Every Kotlin compilation (Android, JVM host tests, iOS) fails on a compiler warning, including
 * the experimental unused-return-value checker (discard a result explicitly with `val _ =`).
 *
 * `extraWarnings` stays off (issue #92): its findings were all in Room's KSP-generated DAOs or
 * false positives (a `var` reset inside a callback, a self-referencing `lateinit` callback).
 */
internal fun Project.configureKotlinCompilerChecks() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xreturn-value-checker=check")
        }
    }
}
