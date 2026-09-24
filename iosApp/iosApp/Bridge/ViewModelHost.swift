// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCore
import Shared

/// A shared Kotlin ViewModel as Swift sees it (ADR 0001, "Decision (R2)").
///
/// The Kotlin side declares, in `shared/src/iosMain/.../NativeViewModels.kt`:
///
///     @NativeCoroutinesState val FooViewModel.state: StateFlow<FooUiState> get() = uiState
///
/// which KMP-NativeCoroutines exports as `state` (the current value) and `stateFlow`. Then one line
/// here makes the ViewModel usable with ``ViewModelHost``:
///
///     extension FooViewModel: SharedViewModel {}
protocol SharedViewModel: Lifecycle_viewmodelViewModel {
    associatedtype State
    associatedtype Event

    /// The current UI state (`StateFlow.value`).
    var state: State { get }
    /// Every UI state, as a KMP-NativeCoroutines flow.
    var stateFlow: NativeFlow<State, Error, KotlinUnit> { get }

    func onEvent(event: Event)
}

extension DashboardViewModel: SharedViewModel {}

/// Owns one shared Kotlin ViewModel for one SwiftUI screen, the way an Android
/// `ViewModelStoreOwner` does:
///
/// - publishes its `uiState` as ``state``, collected on the main actor for the host's lifetime;
/// - forwards user intents with ``send(_:)``;
/// - collects one-shot effects with ``collect(_:perform:)``, from a view's `.task`;
/// - clears the ViewModel (`onCleared()`, cancelling `viewModelScope`) when it's released.
///
/// Hold it in a `@StateObject`, so SwiftUI builds the ViewModel once per screen:
///
///     @StateObject private var host = ViewModelHost(KoinHelper().dashboardViewModel())
@MainActor
final class ViewModelHost<VM: SharedViewModel>: ObservableObject {
    let viewModel: VM
    @Published private(set) var state: VM.State

    private let store = ViewModelStoreHolder()
    private var stateTask: Task<Void, Never>?

    init(_ viewModel: VM) {
        self.viewModel = viewModel
        state = viewModel.state
        store.adopt(viewModel: viewModel)
        let states = asyncSequence(for: viewModel.stateFlow)
        stateTask = Task { [weak self] in
            do {
                for try await value in states {
                    guard let self else { return }
                    self.state = value
                }
            } catch {
                // A StateFlow never fails; cancellation ends the loop.
            }
        }
    }

    deinit {
        stateTask?.cancel()
        store.clear()
    }

    /// Sends a user intent to the ViewModel (`onEvent`).
    func send(_ event: VM.Event) {
        viewModel.onEvent(event: event)
    }

    /// Collects a one-shot effect flow until the calling task is cancelled. Call it from the
    /// screen's `.task { }`, so effects are handled only while the screen is on screen:
    ///
    ///     .task { await host.collect(host.viewModel.sideEffects) { effect in … } }
    func collect<Effect>(
        _ effects: @escaping NativeFlow<Effect, Error, KotlinUnit>,
        perform handle: @MainActor (Effect) -> Void
    ) async {
        do {
            for try await effect in asyncSequence(for: effects) {
                handle(effect)
            }
        } catch {
            // Effects flows don't fail; cancellation ends the loop.
        }
    }
}
