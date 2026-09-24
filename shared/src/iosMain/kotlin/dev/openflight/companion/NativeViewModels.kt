// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.openflight.companion.feature.dashboard.DashboardEffect
import dev.openflight.companion.feature.dashboard.DashboardUiState
import dev.openflight.companion.feature.dashboard.DashboardViewModel
import dev.openflight.companion.feature.range.DrivingRangeUiState
import dev.openflight.companion.feature.range.DrivingRangeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
