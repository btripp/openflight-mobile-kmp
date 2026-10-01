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
 *
 * Modules with the Compose compiler (`openflight.android.application`,
 * `openflight.android.library.compose`, which apply it before this plugin) also run the
 * compose-rules ktlint rule set (`io.nlopez.compose.rules:ktlint`), so `spotlessCheck` flags
 * Compose API issues there. Its settings (e.g. `compose_content_emitters`) are in `.editorconfig`.
 */
class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            val ktlintVersion = libs.version("ktlint")
            val isRoot = this == rootProject
            val composeRules =
                if (pluginManager.hasPlugin(COMPOSE_COMPILER_PLUGIN)) {
                    val rules = libs.library("compose-rules-ktlint").get()
                    listOf("${rules.module}:${rules.versionConstraint.requiredVersion}")
                } else {
                    emptyList()
                }
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
                    ktlint(ktlintVersion).customRuleSets(composeRules)
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
        const val COMPOSE_COMPILER_PLUGIN = "org.jetbrains.kotlin.plugin.compose"
        const val SPDX_HEADER = "// SPDX-License-Identifier: AGPL-3.0-or-later\n"
    }
}
