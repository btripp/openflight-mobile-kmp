// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `openflight.android.library.compose`: an Android-only Jetpack Compose library (ADR 0001).
 * Used by `core:designsystem` and the `feature:<name>:ui` modules.
 *
 * - `com.android.library` with AGP 9's built-in Kotlin, plus the Compose compiler.
 * - SDK levels from the catalog, Java/Kotlin 17 bytecode, spotless and detekt.
 * - Jetpack Compose from the BOM ([addJetpackComposeDependencies]).
 * - JVM unit tests in `src/test` (kotlin.test on JUnit 4 + assertk) and device UI tests in
 *   `src/androidTest`: `./gradlew :<module>:connectedDebugAndroidTest` (needs an emulator).
 * - An `allTests` task that runs the JVM unit tests, so `./gradlew allTests` covers these
 *   modules the same way it covers the KMP ones.
 *
 * Namespace defaults to `dev.openflight.companion.<gradle path>`, so `:feature:range:ui` becomes
 * `dev.openflight.companion.feature.range.ui`.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("openflight.spotless")
            pluginManager.apply("openflight.detekt")

            extensions.configure<LibraryExtension> {
                namespace = defaultNamespace()
                compileSdk = libs.version("android-compileSdk").toInt()
                defaultConfig {
                    minSdk = libs.version("android-minSdk").toInt()
                    testInstrumentationRunner = TEST_INSTRUMENTATION_RUNNER
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildFeatures {
                    compose = true
                }
                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                    }
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                compilerOptions {
                    jvmTarget.set(OPENFLIGHT_JVM_TARGET)
                }
            }

            addJetpackComposeDependencies()
            dependencies {
                add("testImplementation", libs.library("kotlin-test-junit"))
                add("testImplementation", libs.library("assertk"))
            }

            tasks.register("allTests") {
                group = "verification"
                description = "Runs the JVM unit tests (parity with the KMP modules' allTests)."
                dependsOn("testDebugUnitTest")
            }
        }
    }
}
