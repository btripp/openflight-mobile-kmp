// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `openflight.kmp.compose`: `openflight.kmp.library` + Compose Multiplatform.
 *
 * Adds the Compose compiler, the CMP runtime/foundation/ui/material3/resources
 * artifacts to commonMain, and enables Android resources so Compose resources work
 * in the Android KMP library target.
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("openflight.kmp.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<KotlinMultiplatformExtension> {
                extensions.getByType<KotlinMultiplatformAndroidLibraryTarget>().apply {
                    androidResources {
                        enable = true
                    }
                }

                sourceSets.getByName("commonMain").dependencies {
                    implementation(libs.library("compose-runtime"))
                    implementation(libs.library("compose-foundation"))
                    implementation(libs.library("compose-ui"))
                    implementation(libs.library("compose-material3"))
                    implementation(libs.library("compose-components-resources"))
                    implementation(libs.library("compose-uiToolingPreview"))
                }
                sourceSets.getByName("androidMain").dependencies {
                    implementation(libs.library("compose-uiToolingPreview"))
                }
            }

            dependencies {
                add("androidRuntimeClasspath", libs.library("compose-uiTooling"))
            }
        }
    }
}
