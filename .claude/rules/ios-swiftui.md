---
paths:
  - "iosApp/**/*.swift"
  - "shared/src/iosMain/**/*.kt"
---

# iOS SwiftUI app (iosApp) and its Kotlin bridge

- State and logic live in the shared Kotlin ViewModels. SwiftUI only renders `UiState` and sends
  events; don't reimplement ViewModel logic in Swift.
- Screens split into a `<Name>View` that owns the ViewModel through
  `@StateObject private var host = ViewModelHost(KoinHelper().<name>ViewModel())` and collects
  `host.viewModel.sideEffects` in `.task`, plus a stateless `<Name>Content(state:send:)` that
  previews and tests can build directly.
- A ViewModel becomes usable from Swift with `extension FooViewModel: SharedViewModel {}` in
  `Bridge/ViewModelHost.swift`, backed by the `@NativeCoroutinesState val FooViewModel.state` /
  `@NativeCoroutines val FooViewModel.sideEffects` declarations in
  `shared/src/iosMain/kotlin/dev/openflight/companion/NativeViewModels.kt`. Change both sides
  together.
- Sealed Kotlin types arrive as class hierarchies (`DashboardUiStateLive`,
  `DashboardEffectNewShot`). Match them with `as?` / `is`.
- Colors, type and shared components come from `DesignSystem/` (`Theme`, `Typography`,
  `Components.swift`), which mirror `core:designsystem`'s tokens. Don't use system colors.
- Accessibility identifiers come from the shared `<Name>TestTags` objects
  (`.accessibilityIdentifier(CalibrationTestTags.shared.HOST_FIELD)`). Don't write string literals.
- Navigation (plan F1d): `AppTab` in `AppRoot.swift` mirrors Android's `TopLevelDestination`
  (same labels, same order); pushed screens are `AppRoute` cases rendered by
  `AppRouteDestination`. XCUITests open a destination with `AppNav.open(.settings, in: app)`
  (tab bar, floating tab or iPad sidebar), never `app.tabBars.buttons[...]` directly, and must
  pass on an iPhone and an iPad simulator; on iPad, Session/History show list and detail at once.
- XCTest only discovers methods that start with `test`, so iOS tests are the exception to the
  "no `test` prefix" rule: `testPreviewShotShowsMetricsAndClubMenu()`.
- Every Swift file starts with `// SPDX-License-Identifier: AGPL-3.0-or-later`.
- Verify with invariant 9 in CLAUDE.md (`xcodebuild … build`). The Xcode build phase rebuilds the
  Shared framework itself.
