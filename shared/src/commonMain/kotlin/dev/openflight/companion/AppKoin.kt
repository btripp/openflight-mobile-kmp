// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesIgnore
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.LifecycleConnectionPolicy
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.dataModule
import dev.openflight.companion.feature.calibration.calibrationModule
import dev.openflight.companion.feature.camera.cameraModule
import dev.openflight.companion.feature.dashboard.dashboardModule
import dev.openflight.companion.feature.range.rangeModule
import dev.openflight.companion.feature.session.sessionModule
import dev.openflight.companion.feature.settings.settingsModule
import dev.openflight.companion.feature.training.trainingModule
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** Every module the app graph needs. Android adds `androidContext(...)` when it starts Koin. */
val appModules: List<Module> =
    listOf(
        dataModule,
        dashboardModule,
        calibrationModule,
        rangeModule,
        sessionModule,
        trainingModule,
        cameraModule,
        settingsModule,
    )

/**
 * Starts the app's Koin graph ([appModules] then [extraModules], so an extra module can override a
 * binding) once per process, and returns it. A second call returns the running graph unchanged:
 * SwiftUI may build its root view again, and a second `startKoin` throws.
 *
 * @param appDeclaration platform setup, e.g. Android's `androidContext(application)`.
 */
fun initKoin(
    extraModules: List<Module> = emptyList(),
    appDeclaration: KoinAppDeclaration = {},
): Koin {
    KoinPlatform.getKoinOrNull()?.let { return it }
    return startKoin {
        appDeclaration()
        modules(appModules + extraModules)
    }.koin
}

/**
 * Applies the debug [LaunchOptions] to a started Koin graph, before any UI resolves the
 * repositories: overrides [ShotRepository] with [PreviewShotRepository] when asked, then persists
 * the seeded settings. The options themselves are bound too, for the UI hooks (the Android nav
 * host reads them through [launchOptions]). Callers gate this on a debug build and call it once
 * per process.
 */
@NativeCoroutinesIgnore // Kotlin-only: iOS applies the options inside startKoinForIos().
suspend fun Koin.applyLaunchOptions(options: LaunchOptions) {
    loadModules(listOf(module { single { options } }), allowOverride = true)
    if (options.usesFakeRepository) {
        loadModules(listOf(previewModule(options.previewShot)), allowOverride = true)
    }
    val settings = get<SettingsRepository>()
    options.transport?.let { settings.setTransport(it) }
    options.host?.let { settings.setHost(it) }
}

private fun previewModule(showPreviewShot: Boolean): Module =
    module {
        // Deletes and Clear edit the preview history in memory, like the real repository.
        single<ShotRepository> {
            LocalEditsShotRepository(PreviewShotRepository(settings = get(), showPreviewShot = showPreviewShot))
        }
    }

/**
 * The app-wide [AppLifecycle] the platform shell reports foreground/background to (plan R8d),
 * with its [LifecycleConnectionPolicy] started. Call after [applyLaunchOptions]: the policy binds
 * to whichever [ShotRepository] the graph holds at that point (the preview one in UI tests).
 */
fun Koin.appLifecycle(): AppLifecycle {
    get<LifecycleConnectionPolicy>().start()
    return get()
}

/** The debug [LaunchOptions] applied at launch, or the defaults (release builds, no hooks). */
fun Koin.launchOptions(): LaunchOptions = getOrNull<LaunchOptions>() ?: LaunchOptions()
