// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `openflight.android.application`: the Android app module.
 *
 * AGP 9 compiles Kotlin itself (built-in Kotlin), so no `kotlin-android` plugin is
 * applied. Adds the Compose compiler and Jetpack Compose from the BOM
 * ([addJetpackComposeDependencies], including the `src/androidTest` UI test stack), SDK levels
 * from the catalog, Java/Kotlin 17 bytecode, spotless and detekt.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("openflight.spotless")
            pluginManager.apply("openflight.detekt")

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.version("android-compileSdk").toInt()
                defaultConfig {
                    minSdk = libs.version("android-minSdk").toInt()
                    targetSdk = libs.version("android-targetSdk").toInt()
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
        }
    }
}
