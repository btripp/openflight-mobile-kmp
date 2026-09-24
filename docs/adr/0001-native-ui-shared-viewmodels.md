# ADR 0001: Native UI per platform, shared ViewModels in KMP

- **Status:** Accepted, 2026-09-24
- **Supersedes:** plan §1 row "Shared UI: Compose Multiplatform on both Android and iOS"

## Context

Steps S1 through S9 built the app with a single Compose Multiplatform UI running on both
Android and iOS. After S9 the product owner decided that each platform's UI must be native:

- **Android:** Jetpack Compose.
- **iOS:** SwiftUI.

They also chose to keep presentation logic shared: ViewModels and UI state stay in Kotlin
Multiplatform.

## Decision

1. **Shared (KMP, commonMain, no Compose):**
   - every `core:*` module: model, protocol, ble, network, data, flight and sensors
   - every feature's `ViewModel`, sealed `UiState` and event types, plus pure presentation
     helpers such as formatters and projection math
2. **Android UI:**
   - Jetpack Compose only. It lives in `androidMain` of the feature modules, or in
     Android-only modules.
   - `core:designsystem` becomes an Android library with the same `Of*` wrappers, and the
     ForbiddenImport rule stays.
   - Navigation uses Jetpack `navigation-compose` in `androidApp`.
3. **iOS UI:**
   - SwiftUI in `iosApp`, consuming one umbrella framework, `Shared.framework`. It exports
     `core:data`, `core:model` and the feature ViewModels, plus a Koin bootstrap and ViewModel
     accessors for Swift.
   - Kotlin `Flow`/`StateFlow` and `suspend` calls cross to Swift through an interop layer:
     SKIE if it supports the project's Kotlin version, otherwise KMP-NativeCoroutines, and
     otherwise thin hand-written callback wrappers. Step R2 decides and records which.
   - The upstream SwiftUI app (`jake-fishtech/openflight@feat/iOS-ble`, AGPL-3.0) is the
     reference for the views, including the RealityKit driving range. Its view code may be
     ported directly; its managers are replaced by the shared ViewModels.
4. **The Compose Multiplatform plugin and dependencies are removed from the build.**

## Consequences

- iOS gets native look and behaviour, native accessibility, and **XCUITest**. That also closes
  the earlier gap where the simulator could not be driven by taps.
- The iOS driving range can use RealityKit (true 3D) again, driven by the shared
  `core:flight` trajectory. Android keeps the Compose `Canvas` 2.5D renderer.
- Every UI screen now exists twice, once per platform. Behaviour parity is protected because
  both platforms render the same shared `UiState` and use the same ViewModels and tests.
- The Kotlin↔Swift interop layer is new risk: Flow and suspend calls bridged into Swift, and
  ViewModel lifecycle and `onCleared` driven from SwiftUI.
- With no Raspberry Pi available, all verification runs on simulators and emulators against
  `openflight-server --mock`. `docs/hardware-test-matrix.md` stays open for later.

## Decision (R2): Kotlin↔Swift interop is KMP-NativeCoroutines 1.0.6

Checked on 2026-09-24:

- **SKIE: not usable.** Its docs (`skie.touchlab.co/intro`) say "compatible with Kotlin versions
  from 2.0.0 up to 2.4.10". The newest release, 0.10.14 (Maven Central, 2026-07-27), adds
  "Support for Kotlin 2.4.10". Nothing supports 2.4.20, and SKIE is a compiler plugin that needs
  an exact Kotlin match.
- **KMP-NativeCoroutines: usable.** Release v1.0.6 (Gradle plugin portal + Maven Central,
  2026-09-07) says "Updated Kotlin to 2.4.20". It runs as a compiler plugin with no KSP. The Swift
  package tag `1.0.6` matches it.

How it's wired:

- The Gradle plugin `com.rickclephas.kmp.nativecoroutines` is applied to `shared` only. The
  version is in `libs.versions.toml` and must stay in step with the Swift package pinned in
  `iosApp.xcodeproj`.
- `feature:*` stays free of interop annotations. `shared/src/iosMain/.../NativeViewModels.kt`
  declares one annotated extension per ViewModel, and every ViewModel uses the same names:
  - `@NativeCoroutinesState val XViewModel.state` becomes Swift `state` (the current value) and
    `stateFlow`.
  - `@NativeCoroutines val XViewModel.sideEffects` exposes the one-shot effects.
- `ViewModelStoreHolder` (iosMain) registers a ViewModel in a `ViewModelStore`. Clearing the
  store runs the protected `onCleared()` and cancels `viewModelScope`.
- Swift: `ViewModelHost<VM: SharedViewModel>` (`iosApp/iosApp/Bridge/ViewModelHost.swift`)
  owns a ViewModel.
  - It collects `stateFlow` as an `AsyncSequence` (`KMPNativeCoroutinesAsync`) on the main
    actor into `@Published state`.
  - `send(_:)` forwards events.
  - `collect(_:perform:)` handles effects from a view's `.task`.
  - It clears the store on `deinit`.
- Sealed types have no `onEnum(of:)` (that's a SKIE feature), so Swift uses `is`/`as?` checks
  on the exported classes.

Revisit this choice when SKIE supports the project's Kotlin version.
