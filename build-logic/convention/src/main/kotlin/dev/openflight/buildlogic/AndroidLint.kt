// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.buildlogic

import com.android.build.api.dsl.Lint

/**
 * Android Lint for `openflight.android.application` and `openflight.android.library.compose`
 * (`./gradlew lintDebug`, part of the invariant chain). Warnings fail the build, so a finding is
 * fixed or suppressed at its site with a reason (`@Suppress("Id")`, `tools:ignore="Id"`).
 */
internal fun Lint.configureOpenFlightLint() {
    abortOnError = true
    warningsAsErrors = true
    // Dependabot owns upgrades, and these checks look up the network, so a new release upstream
    // would break an unchanged build.
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    // minSdk 26 always uses the adaptive icon (mipmap-anydpi); the legacy PNGs are never shown.
    disable += "IconLauncherShape"
    // AGP 9.5's lint rejects the checks bundled in lifecycle-runtime 2.11.0 (built against an
    // older lint API) and only skips them, so the finding is the library's, not ours. Drop this
    // once a stable lifecycle release passes under the AGP in use.
    disable += "ObsoleteLintCustomCheck"
}
