// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `openflight.kmp.library`: a Kotlin Multiplatform library for Android + iOS.
 *
 * - Android target via `com.android.kotlin.multiplatform.library` with **host (JVM)
 *   tests enabled**; that plugin enables no test variants by default.
 * - iOS targets `iosArm64` and `iosSimulatorArm64`.
 * - commonTest gets kotlin.test, assertk, Turbine and kotlinx-coroutines-test.
 * - Applies `openflight.spotless` and `openflight.detekt`.
 *
 * Namespace defaults to `dev.openflight.companion.<gradle path>`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("openflight.spotless")
            pluginManager.apply("openflight.detekt")

            extensions.configure<KotlinMultiplatformExtension> {
                extensions.getByType<KotlinMultiplatformAndroidLibraryTarget>().apply {
                    namespace = defaultNamespace()
                    compileSdk = libs.version("android-compileSdk").toInt()
                    minSdk = libs.version("android-minSdk").toInt()
                    compilerOptions {
                        jvmTarget.set(OPENFLIGHT_JVM_TARGET)
                    }
                    withHostTest {
                        isIncludeAndroidResources = true
                    }
                }

                iosArm64()
                iosSimulatorArm64()

                applyDefaultHierarchyTemplate()

                sourceSets.getByName("commonTest").dependencies {
                    implementation(libs.library("kotlin-test"))
                    implementation(libs.library("assertk"))
                    implementation(libs.library("turbine"))
                    implementation(libs.library("kotlinx-coroutines-test"))
                }
            }
        }
    }
}
