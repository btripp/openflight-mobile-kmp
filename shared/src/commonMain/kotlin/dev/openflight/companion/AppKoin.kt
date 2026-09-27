// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesIgnore
import dev.openflight.companion.core.data.ActiveGameRepository
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.DataBindings
import dev.openflight.companion.core.data.DemoModeRepository
import dev.openflight.companion.core.data.DemoShotHistoryRepository
import dev.openflight.companion.core.data.FinalShotStream
import dev.openflight.companion.core.data.LifecycleConnectionPolicy
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.dataModule
import dev.openflight.companion.core.speech.ScreenReaderMonitor
import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.speech.speechModule
import dev.openflight.companion.feature.bag.bagModule
import dev.openflight.companion.feature.calibration.calibrationModule
import dev.openflight.companion.feature.camera.cameraModule
import dev.openflight.companion.feature.dashboard.dashboardModule
import dev.openflight.companion.feature.games.gamesModule
import dev.openflight.companion.feature.range.rangeModule
import dev.openflight.companion.feature.session.sessionModule
import dev.openflight.companion.feature.settings.settingsModule
import dev.openflight.companion.feature.training.trainingModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

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
        // Plan F4: core:speech's SpeechEngine binding, added at the end to keep this list's diff
        // mergeable with the other wave-1/wave-2 steps that also touch it (§4a A7).
        speechModule,
        // Plan F5: the bag screens (appended, §4a A7).
        bagModule,
        // Plan F7: the shot call-out coordinator, app-scoped (see Koin.shotCallouts() below).
        calloutModule(),
        // Plan F9a: the games and Activities ViewModels.
        gamesModule,
        // Plan F14: Demo mode, and the switch in front of the Pi-facing repositories (must stay after
        // dataModule: it replaces dataModule's default ShotRepository, PiSessionRepository and
        // ShotHistoryRepository bindings).
        demoModule(),
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
        loadModules(listOf(previewModule(options.previewShot, options.previewLiveShots)), allowOverride = true)
    }
    if (options.previewHistory || options.previewHistoryStuck || options.previewHistoryBulk) {
        loadModules(
            listOf(previewHistoryModule(stuck = options.previewHistoryStuck, bulk = options.previewHistoryBulk)),
            allowOverride = true,
        )
    }
    if (options.previewPiSession || options.previewPiSessionStuck) {
        val real = get<PiSessionRepository>()
        val preview =
            PreviewPiSessionRepository(
                delegate = real,
                answerDelayMillis =
                    if (options.previewPiSessionStuck) null else PreviewPiSessionRepository.DEFAULT_ANSWER_DELAY_MILLIS,
            )
        loadModules(listOf(module { single<PiSessionRepository> { preview } }), allowOverride = true)
    }
    if (options.previewPi) {
        // Plan R8f part A: profiles, device cards and shutdown without a Pi.
        loadModules(
            listOf(module { single<PiSessionRepository> { PreviewDevicePiSessionRepository() } }),
            allowOverride = true,
        )
    }
    if (options.previewPiMock) {
        // Plan F8d-B: a `mock_mode` preview Pi whose simulate delivers a new preview shot through
        // the live path. The shot repository is looked up per simulate, so it's never built early.
        val koin = this
        val mockPi =
            PreviewDevicePiSessionRepository(mockMode = true) { number ->
                (koin.get<ShotRepository>() as? LocalEditsShotRepository)
                    ?.deliver(PreviewShotRepository.simulatedShot(number))
            }
        loadModules(listOf(module { single<PiSessionRepository> { mockPi } }), allowOverride = true)
    }
    val settings = get<SettingsRepository>()
    applyDemoOptions(options, settings)
    options.transport?.let { settings.setTransport(it) }
    options.host?.let { settings.setHost(it) }
    options.rangeTheme?.let { settings.setRangeTheme(it) }
    options.shotTrail?.let { settings.setShotTrail(it) }
}

/**
 * Plan F14's launch hooks: a scripted launch starts with Demo mode off unless it asks for it
 * (`--demo-mode on`), so a test never inherits a Demo mode left on by an earlier run; and
 * `--callout-probe` writes call-outs down instead of speaking them, for the iOS UI tests.
 */
private suspend fun Koin.applyDemoOptions(
    options: LaunchOptions,
    settings: SettingsRepository,
) {
    if (options != LaunchOptions()) getOrNull<DemoModeRepository>()?.setEnabled(options.demoMode ?: false)
    if (options.calloutProbe) {
        val probe = CalloutProbeSpeechEngine()
        loadModules(listOf(module { single<SpeechEngine> { probe } }), allowOverride = true)
        settings.setCalloutsEnabled(true)
    }
}

private fun previewModule(
    showPreviewShot: Boolean,
    liveShots: Boolean,
): Module =
    module {
        // Deletes and Clear edit the preview history in memory, like the real repository.
        single<ShotRepository> {
            LocalEditsShotRepository(
                PreviewShotRepository(settings = get(), showPreviewShot = showPreviewShot),
                pi = get(),
                liveShotIntervalMillis = if (liveShots) LaunchOptions.PREVIEW_LIVE_SHOT_INTERVAL_MILLIS else null,
            )
        }
    }

/** Plan R8f: stored sessions to show and edit without a Pi (debug launch hooks only). */
private fun previewHistoryModule(
    stuck: Boolean,
    bulk: Boolean,
): Module =
    module {
        single<ShotHistoryRepository> {
            PreviewShotHistoryRepository(
                writeDelayMillis = if (stuck) null else PreviewShotHistoryRepository.DEFAULT_WRITE_DELAY_MILLIS,
                bulkShots = if (bulk) LaunchOptions.PREVIEW_BULK_SHOTS else 0,
            )
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

// Plan F7: the shot call-out coordinator, added at the end to keep this file's diff mergeable
// with the other wave-2 steps that also touch it (§4a A7).

/**
 * [ShotCalloutCoordinator]'s Koin binding, in its own module so a test graph can swap it out. A
 * function, not a top-level `val`: [appModules] (declared earlier in this file) references it,
 * and top-level `val`s initialize in file order, unlike class members — a `val` here would be
 * "must be initialized" at [appModules]'s own initialization.
 */
private fun calloutModule(): Module =
    module {
        single {
            ShotCalloutCoordinator(
                finalShots = get<FinalShotStream>(),
                settings = get<SettingsRepository>(),
                activeGame = get<ActiveGameRepository>(),
                // Lazy: see ShotCalloutCoordinator's class doc for why this must not resolve
                // (and so build the real, DataStore-backed implementation) merely by being
                // constructed.
                conditions = lazy { get<ConditionsRepository>() },
                speech = get<SpeechEngine>(),
                screenReader = get<ScreenReaderMonitor>(),
                lifecycle = get<AppLifecycle>(),
            )
        }
    }

/**
 * The app-wide [ShotCalloutCoordinator], started (idempotent) the first time this is called.
 * Call once per process, after [appLifecycle] so both share the same [AppLifecycle] instance
 * (Android calls this from `OpenFlightApplication.onCreate`; iOS from `iOSApp.init`).
 */
fun Koin.shotCallouts(): ShotCalloutCoordinator {
    val coordinator = get<ShotCalloutCoordinator>()
    coordinator.start()
    return coordinator
}

// Plan F14: Demo mode, added at the end to keep this file's diff mergeable (§4a A7).

/**
 * Demo mode ([DemoController]) and the app's Pi-facing repositories: each is a switch in front of
 * `dataModule`'s real implementation (bound under [DataBindings.Real]) and Demo mode's pretend Pi, so
 * every ViewModel keeps its interface and Demo mode turns on and off without a relaunch. A function
 * for the same initialization-order reason as [calloutModule].
 */
private fun demoModule(): Module =
    module {
        single {
            DemoController(
                settings = get(),
                history = get<DemoShotHistoryRepository>(DataBindings.DemoHistory),
                mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                clock = { Clock.System.now().toEpochMilliseconds() },
            )
        }
        single<DemoModeRepository> { get<DemoController>() }
        single<ShotRepository> {
            val demo = get<DemoController>()
            DemoSwitchingShotRepository(real = get(DataBindings.Real), demo = demo.shots, switch = demo)
        }
        single<PiSessionRepository> {
            val demo = get<DemoController>()
            DemoSwitchingPiSessionRepository(real = get(DataBindings.Real), demo = demo.pi, switch = demo)
        }
        single<ShotHistoryRepository> {
            DemoSwitchingShotHistoryRepository(
                real = get(DataBindings.Real),
                demo = get<DemoShotHistoryRepository>(DataBindings.DemoHistory),
                switch = get<DemoController>(),
            )
        }
    }
