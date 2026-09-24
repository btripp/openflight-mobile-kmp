# OpenFlight Companion (KMP): project conventions

Wire protocol facts, client behaviour to preserve and the step plan live in
`plans/openflight-kmp-app.md` (§0 = ground truth). Read that first. Don't infer protocol
details from memory.

## Build environment
- There's no system JDK on the dev machine. Use
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- `local.properties` (gitignored) holds `sdk.dir`.
- Package root and applicationId: `dev.openflight.companion`.

## Architecture (ADR 0001: native UI per platform, shared ViewModels)
- Android UI is **Jetpack Compose** (Compose BOM), iOS UI is **SwiftUI**. There is no Compose
  Multiplatform anywhere in the build.
- `feature:<name>` modules are KMP and hold only the `ViewModel`, `UiState`, events, effects
  and pure helpers. Their `commonMain` must not mention Compose; `verifyNoComposeInCommonMain`
  (run by `allTests`/`check`) enforces it.
- `feature:<name>:ui` modules are Android-only Jetpack Compose (screens, routes, device UI
  tests). `core:designsystem` is Android-only too.

## Module graph (arrows point from a module to its dependencies; core never depends on feature/app)
```
androidApp (Jetpack NavHost, permissions, launch extras, Koin start)
    ├──> feature:dashboard:ui | feature:calibration:ui | feature:range:ui
    │        └──> its own feature:<name> + core:designsystem + core:* (never another feature)
    └──> shared (KMP: initKoin, LaunchOptions, PreviewShotRepository)
iosApp (SwiftUI, Xcode) ──> Shared.framework = shared, exporting core:model/data/insights + feature:*
shared ──> feature:dashboard | feature:calibration | feature:range | feature:session (KMP, VMs)
             └──> core:data ──> core:ble / core:network ──> core:protocol ──> core:model
feature:range ──> core:flight ; feature:calibration ──> core:sensors ; dashboard/session ──> core:insights
```

## Adding a module
1. Add `include(":core:foo")` to `settings.gradle.kts`.
2. In `core/foo/build.gradle.kts`, add `plugins { alias(libs.plugins.openflight.kmp.library) }`
   for shared (KMP) code, or `openflight.android.library.compose` for an Android-only Jetpack
   Compose module (a `feature:<name>:ui` or `core:designsystem`).
3. The namespace defaults to `dev.openflight.companion.core.foo`. For KMP: Android + iosArm64 +
   iosSimulatorArm64 targets, Android host tests and the commonTest deps. For Android Compose:
   the Compose BOM, `src/test` (JUnit 4 kotlin.test + assertk) and `src/androidTest` device UI
   tests. Spotless and detekt come with both.
4. Depend on other modules through typesafe accessors: `implementation(projects.core.model)`.

Convention plugin ids: `openflight.kmp.library`, `openflight.android.library.compose`,
`openflight.android.application`, `openflight.spotless`, `openflight.detekt`
(`build-logic/convention`).

## Tests
- commonTest: `kotlin.test` + **assertk** (Truth-style assertions), **Turbine** for Flows,
  `kotlinx-coroutines-test` `runTest`. Android-only tests may use Truth.
- No `test` prefix on test names. Instrumented/UI tests use `given_when_then` or `when_then`,
  on `ComponentActivity` (`createAndroidComposeRule<ComponentActivity>()`).
- Device UI tests: `./gradlew :feature:<name>:ui:connectedDebugAndroidTest` and
  `:androidApp:connectedDebugAndroidTest` (need an emulator; not part of `allTests`).
- Port assertions and numbers from the reference `ios/OpenFlightTests`. Don't reinvent them.

## Invariants (check after every change)
1. `./gradlew spotlessCheck detekt` passes. Run `spotlessApply` first.
2. `./gradlew allTests` passes. It runs `testAndroidHostTest`, `iosSimulatorArm64Test`, the
   Android-only modules' `testDebugUnitTest` and `verifyNoComposeInCommonMain`.
3. `./gradlew :androidApp:assembleDebug :shared:linkDebugFrameworkIosSimulatorArm64`
   succeeds.
4. No `core:*` module depends on `feature:*`, `shared` or an app module, and no feature
   module (or its `:ui`) depends on another feature.
5. Feature UIs use only the `core:designsystem` wrappers (`Of*`), never raw Material3.
6. No Android framework types in `commonMain`, and none in the public API of `core:data` or
   `core:model`.
7. Don't commit generated screenshot goldens (they're gitignored).
8. Every Kotlin file carries `// SPDX-License-Identifier: AGPL-3.0-or-later`. Spotless adds
   and enforces it.
