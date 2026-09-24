// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `openflight.spotless`: ktlint formatting plus the AGPL SPDX header on every Kotlin
 * source file (invariant 8). Rule tweaks live in the root `.editorconfig`.
 *
 * Applied to the root project too, where it also covers `build-logic` and the root
 * `*.gradle.kts` files.
 */
class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            val ktlintVersion = libs.version("ktlint")
            val isRoot = this == rootProject
            extensions.configure<SpotlessExtension> {
                // Root every target at explicit dirs. A glob target walks the whole project
                // dir, including build/ outputs that code generators rewrite concurrently.
                kotlin {
                    target(
                        fileTree(if (isRoot) "build-logic" else "src") {
                            include("**/*.kt")
                            exclude("**/build/**")
                        },
                    )
                    ktlint(ktlintVersion)
                    licenseHeader(SPDX_HEADER)
                }
                kotlinGradle {
                    val scripts =
                        fileTree(projectDir) {
                            include("*.gradle.kts")
                        }
                    val buildLogicScripts =
                        fileTree("build-logic") {
                            include("*.gradle.kts", "*/*.gradle.kts")
                        }
                    target(if (isRoot) scripts + buildLogicScripts else scripts)
                    ktlint(ktlintVersion)
                }
            }
        }
    }

    private companion object {
        const val SPDX_HEADER = "// SPDX-License-Identifier: AGPL-3.0-or-later\n"
    }
}
