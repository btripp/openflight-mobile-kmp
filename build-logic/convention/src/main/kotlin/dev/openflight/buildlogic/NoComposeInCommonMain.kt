// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * ADR 0001: shared code is presentation logic only, so no KMP module's `commonMain` may mention
 * Compose (`androidx.compose` or `org.jetbrains.compose`). Registers
 * `verifyNoComposeInCommonMain` and runs it as part of `allTests` and `check`.
 */
internal fun Project.registerNoComposeInCommonMainCheck() {
    val sources =
        fileTree("src/commonMain") {
            include("**/*.kt")
        }
    val verify =
        tasks.register("verifyNoComposeInCommonMain") {
            group = "verification"
            description = "Fails if commonMain mentions Compose (ADR 0001: shared code stays UI-free)."
            inputs.files(sources)
            doLast {
                val offenders =
                    sources.files
                        .filter { file -> COMPOSE_REFERENCE.containsMatchIn(file.readText()) }
                        .map { it.path }
                        .sorted()
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "Compose referenced in commonMain (ADR 0001 keeps it Android-only):\n" +
                            offenders.joinToString("\n"),
                    )
                }
            }
        }
    tasks.matching { it.name == "allTests" || it.name == "check" }.configureEach {
        dependsOn(verify)
    }
}

private val COMPOSE_REFERENCE = Regex("""androidx\.compose|org\.jetbrains\.compose""")
