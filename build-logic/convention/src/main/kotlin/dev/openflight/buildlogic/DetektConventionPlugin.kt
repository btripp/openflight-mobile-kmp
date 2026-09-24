// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * `openflight.detekt`: detekt over **every** source set.
 *
 * detekt's default `source` is only `src/main` and `src/test`, which misses the KMP
 * sets. We point it at the whole `src/` tree (commonMain, androidMain, iosMain,
 * commonTest, androidHostTest, iosTest, and any added later).
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                parallel = true
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                source.setFrom(files("src"))
            }
            tasks.withType<Detekt>().configureEach {
                include("**/*.kt", "**/*.kts")
                exclude("**/build/**")
                jvmTarget = OPENFLIGHT_JVM_TARGET.target
                reports {
                    html.required.set(true)
                    xml.required.set(false)
                    txt.required.set(false)
                    sarif.required.set(false)
                    md.required.set(false)
                }
            }
        }
    }
}
