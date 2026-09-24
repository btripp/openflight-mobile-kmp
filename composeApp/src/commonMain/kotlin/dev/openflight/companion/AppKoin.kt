// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.dataModule
import dev.openflight.companion.feature.calibration.calibrationModule
import dev.openflight.companion.feature.dashboard.dashboardModule
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/** Every module the app graph needs. Android adds `androidContext(...)` when it starts Koin. */
val appModules: List<Module> = listOf(dataModule, dashboardModule, calibrationModule)

/**
 * Applies the debug [LaunchOptions] to a started Koin graph, before any UI resolves the
 * repositories: overrides [ShotRepository] with [PreviewShotRepository] when asked, then persists
 * the seeded settings. Callers gate this on a debug build and call it once per process.
 */
suspend fun Koin.applyLaunchOptions(options: LaunchOptions) {
    if (options.usesFakeRepository) {
        loadModules(listOf(previewModule(options.previewShot)), allowOverride = true)
    }
    val settings = get<SettingsRepository>()
    options.transport?.let { settings.setTransport(it) }
    options.host?.let { settings.setHost(it) }
}

private fun previewModule(showPreviewShot: Boolean): Module =
    module {
        single<ShotRepository> { PreviewShotRepository(settings = get(), showPreviewShot = showPreviewShot) }
    }
