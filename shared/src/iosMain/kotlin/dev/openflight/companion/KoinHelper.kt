// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.feature.calibration.CalibrationViewModel
import dev.openflight.companion.feature.camera.CameraViewModel
import dev.openflight.companion.feature.dashboard.DashboardViewModel
import dev.openflight.companion.feature.range.DrivingRangeViewModel
import dev.openflight.companion.feature.session.SessionViewModel
import dev.openflight.companion.feature.settings.SettingsViewModel
import dev.openflight.companion.feature.training.TrainingViewModel
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
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
        runBlocking { koin.applyLaunchOptions(LaunchOptions.fromArguments(arguments)) }
    }
}

/**
 * ViewModel accessors for Swift (`KoinHelper().dashboardViewModel()`). Each call builds a new
 * ViewModel from the graph; the SwiftUI owner keeps it for the screen's lifetime and releases it
 * through its ViewModel bridge when it goes away (step R2). Koin must be started first
 * ([startKoinForIos]).
 */
class KoinHelper : KoinComponent {
    fun dashboardViewModel(): DashboardViewModel = get()

    fun calibrationViewModel(): CalibrationViewModel = get()

    fun drivingRangeViewModel(): DrivingRangeViewModel = get()

    fun sessionViewModel(): SessionViewModel = get()

    /** The debug [LaunchOptions] applied at launch (defaults in release builds). */
    fun launchOptions(): LaunchOptions = getKoin().launchOptions()

    fun trainingViewModel(): TrainingViewModel = get()

    fun cameraViewModel(): CameraViewModel = get()

    fun settingsViewModel(): SettingsViewModel = get()
}
