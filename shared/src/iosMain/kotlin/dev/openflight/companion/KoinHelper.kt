// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.feature.calibration.CalibrationViewModel
import dev.openflight.companion.feature.camera.CameraViewModel
import dev.openflight.companion.feature.dashboard.DashboardViewModel
import dev.openflight.companion.feature.range.DrivingRangeViewModel
import dev.openflight.companion.feature.session.SessionHistoryDetailViewModel
import dev.openflight.companion.feature.session.SessionHistoryViewModel
import dev.openflight.companion.feature.session.SessionViewModel
import dev.openflight.companion.feature.settings.SettingsViewModel
import dev.openflight.companion.feature.training.TrainingViewModel
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi

/**
 * The Swift entry point: call once from the SwiftUI `App` initializer, as
 * `KoinHelperKt.startKoinForIos()`. Starts Koin once per process ([initKoin]); debug builds then
 * apply the [LaunchOptions] from the process arguments (`--ui-testing`, `--preview-shot`, ...).
 */
@OptIn(ExperimentalNativeApi::class)
fun startKoinForIos() {
    if (KoinPlatform.getKoinOrNull() != null) return
    val koin = initKoin()
    if (Platform.isDebugBinary) {
        val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
        val options = LaunchOptions.fromArguments(arguments)
        runBlocking { koin.applyLaunchOptions(options) }
        if (options.usesFakeRepository) {
            // Let the UI tests delete and clear shots in the preview history.
            val preview = koin.get<ShotRepository>()
            koin.loadModules(
                listOf(module { single<ShotRepository> { LocalEditsShotRepository(preview) } }),
                allowOverride = true,
            )
        }
    }
}

/**
 * ViewModel accessors for Swift (`KoinHelper().dashboardViewModel()`). Each call builds a new
 * ViewModel from the graph; the SwiftUI owner keeps it for the screen's lifetime and releases it
 * through its ViewModel bridge when it goes away (step R2). Koin must be started first
 * ([startKoinForIos]).
 */
@Suppress("TooManyFunctions") // One getter per ViewModel Swift builds.
class KoinHelper : KoinComponent {
    fun dashboardViewModel(): DashboardViewModel = get()

    fun calibrationViewModel(): CalibrationViewModel = get()

    fun drivingRangeViewModel(): DrivingRangeViewModel = get()

    fun sessionViewModel(): SessionViewModel = get()

    /** Plan R8h: the stored sessions, newest first. */
    fun sessionHistoryViewModel(): SessionHistoryViewModel = get()

    /** Plan R8h: one stored session. */
    fun sessionHistoryDetailViewModel(sessionId: String): SessionHistoryDetailViewModel =
        get { parametersOf(sessionId) }

    /** The app-wide shot stream. */
    fun shotRepository(): ShotRepository = get()

    /**
     * The app lifecycle (plan R8d): the SwiftUI shell reports `onForeground()` when the scene
     * becomes active and `onBackground()` when it enters the background; the shared policy then
     * connects or disconnects every transport, like Android's `ProcessLifecycleOwner` source.
     */
    fun appLifecycle(): AppLifecycle = getKoin().appLifecycle()

    /** The debug [LaunchOptions] applied at launch (defaults in release builds). */
    fun launchOptions(): LaunchOptions = getKoin().launchOptions()

    fun trainingViewModel(): TrainingViewModel = get()

    fun cameraViewModel(): CameraViewModel = get()

    fun settingsViewModel(): SettingsViewModel = get()
}
