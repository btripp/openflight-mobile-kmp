// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Jetpack Compose dependencies shared by `openflight.android.application` and
 * `openflight.android.library.compose`: everything versioned by the Compose BOM, tooling for
 * `@Preview`, and the device (instrumented) UI test stack for `src/androidTest`
 * (`createAndroidComposeRule<ComponentActivity>()`).
 */
internal fun Project.addJetpackComposeDependencies() {
    dependencies {
        val bom = platform(libs.library("androidx-compose-bom"))
        add("implementation", bom)
        add("androidTestImplementation", bom)
        add("implementation", libs.library("androidx-compose-runtime"))
        add("implementation", libs.library("androidx-compose-foundation"))
        add("implementation", libs.library("androidx-compose-ui"))
        add("implementation", libs.library("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.library("androidx-compose-ui-tooling"))
        // Declares ComponentActivity for the Compose test rule.
        add("debugImplementation", libs.library("androidx-compose-ui-test-manifest"))

        add("androidTestImplementation", libs.library("kotlin-test"))
        add("androidTestImplementation", libs.library("androidx-compose-ui-test-junit4"))
        add("androidTestImplementation", libs.library("androidx-test-runner"))
        add("androidTestImplementation", libs.library("androidx-test-ext-junit"))
        // ui-test-junit4 pulls an older Espresso that cannot inject input on API 36+.
        add("androidTestImplementation", libs.library("androidx-test-espresso-core"))
    }
}

/** The instrumentation runner every Android module's `src/androidTest` uses. */
internal const val TEST_INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
