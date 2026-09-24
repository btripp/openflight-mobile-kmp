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
