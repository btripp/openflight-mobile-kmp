// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.openflight.companion.feature.calibration.CalibrationUiState
import dev.openflight.companion.feature.calibration.CalibrationViewModel
import dev.openflight.companion.feature.camera.CameraEffect
import dev.openflight.companion.feature.camera.CameraUiState
import dev.openflight.companion.feature.camera.CameraViewModel
import dev.openflight.companion.feature.dashboard.DashboardEffect
import dev.openflight.companion.feature.dashboard.DashboardUiState
import dev.openflight.companion.feature.dashboard.DashboardViewModel
import dev.openflight.companion.feature.range.DrivingRangeUiState
import dev.openflight.companion.feature.range.DrivingRangeViewModel
import dev.openflight.companion.feature.session.SessionEffect
import dev.openflight.companion.feature.session.SessionUiState
import dev.openflight.companion.feature.session.SessionViewModel
import dev.openflight.companion.feature.settings.SettingsEffect
import dev.openflight.companion.feature.settings.SettingsUiState
import dev.openflight.companion.feature.settings.SettingsViewModel
import dev.openflight.companion.feature.training.TrainingUiState
import dev.openflight.companion.feature.training.TrainingViewModel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import platform.Foundation.NSData
import platform.Foundation.create

/*
 * Swift-facing streams for the shared ViewModels (ADR 0001, "Decision (R2)"). KMP-NativeCoroutines
 * turns each annotated property into typed Objective-C properties that the Swift package
 * `KMPNativeCoroutinesAsync` bridges into `AsyncSequence`s:
 *
 * - `@NativeCoroutinesState val X.state` -> Swift `vm.stateValue` (current value) and `vm.stateFlow`.
 * - `@NativeCoroutines val X.sideEffects` -> Swift `vm.sideEffectsNative`.
 *
 * Every ViewModel uses the same names, so the Swift `SharedViewModel` protocol (iosApp
 * `ViewModelHost.swift`) wraps each one the same way. They are extensions here, not annotations in
 * `feature:*`, so the feature modules stay free of iOS interop. Add one block per ViewModel.
 */

@NativeCoroutinesState
val DashboardViewModel.state: StateFlow<DashboardUiState>
    get() = uiState

@NativeCoroutines
val DashboardViewModel.sideEffects: Flow<DashboardEffect>
    get() = effects

/** The range has no one-shot effects: its flight, replay and dwell are all in the state. */
@NativeCoroutinesState
val DrivingRangeViewModel.state: StateFlow<DrivingRangeUiState>
    get() = uiState

/** [CalibrationViewModel] has no one-shot effects, so only `state` is bridged. */
@NativeCoroutinesState
val CalibrationViewModel.state: StateFlow<CalibrationUiState>
    get() = uiState

@NativeCoroutinesState
val SessionViewModel.state: StateFlow<SessionUiState>
    get() = uiState

@NativeCoroutines
val SessionViewModel.sideEffects: Flow<SessionEffect>
    get() = effects

/** [TrainingViewModel] reports failures in its state (`error`), so only `state` is bridged. */
@NativeCoroutinesState
val TrainingViewModel.state: StateFlow<TrainingUiState>
    get() = uiState

@NativeCoroutinesState
val CameraViewModel.state: StateFlow<CameraUiState>
    get() = uiState

@NativeCoroutines
val CameraViewModel.sideEffects: Flow<CameraEffect>
    get() = effects

/**
 * The polled camera preview for Swift, one JPEG still per `NSData`; collecting it is what makes
 * the VM poll the Pi. `conflate()` keeps only the newest frame
 * while Swift decodes the previous one (the async sequence is back-pressured), so a slow decoder
 * drops frames instead of buffering them.
 */
@NativeCoroutines
val CameraViewModel.jpegFrames: Flow<NSData>
    get() = frames.conflate().map { it.toNSData() }

@NativeCoroutinesState
val SettingsViewModel.state: StateFlow<SettingsUiState>
    get() = uiState

@NativeCoroutines
val SettingsViewModel.sideEffects: Flow<SettingsEffect>
    get() = effects

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }
