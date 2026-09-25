# OpenFlight Companion (KMP): project conventions

Wire protocol facts, client behaviour to preserve and the step plan live in
`plans/openflight-kmp-app.md` (§0 = ground truth). Read that first. Don't infer protocol
details from memory.

## Build environment
- Gradle runs on JDK 17+ (bytecode targets 17; CI uses Temurin 21). If there's no system
  JDK, point `JAVA_HOME` at
  Android Studio's bundled JBR, e.g. on macOS
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Adjust the
  path to your install. The Xcode build phase falls back to this same path when `JAVA_HOME`
  is unset.
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
androidApp (Jetpack NavHost, permissions, launch extras, Koin start, app icon)
    ├──> feature:dashboard:ui | feature:calibration:ui | feature:range:ui
    │        feature:session:ui | feature:training:ui | feature:camera:ui | feature:settings:ui
    │        └──> its own feature:<name> + core:designsystem + core:* (never another feature)
    └──> shared (KMP: initKoin, LaunchOptions, PreviewShotRepository)
iosApp (SwiftUI, Xcode) ──> Shared.framework = shared, exporting core:model/data/insights/flight
                             + feature:* + KoinHelper/NativeViewModels bridge; AppIcon asset catalog
shared ──> feature:dashboard | calibration | range | session | training | camera | settings (KMP, VMs)
             ├──> core:data ──> core:ble / core:network / core:socketio ──> core:protocol ──> core:model
             │            ├──> core:database (Room 3 KMP shot history; internal to core:data)
             │            └──> core:flight (conditions-adjusted carry, roll; ConditionsRepository)
             └──> core:speech (SpeechEngine text-to-speech; no core:* deps of its own)
feature:range, feature:session ──> core:flight ; feature:calibration ──> core:sensors ; others ──> core:insights
core:testing → fake repositories shared by VM tests (test-only, no app depends on it directly)
```

Current leaf `core:*` modules: `model`, `protocol`, `ble`, `network`, `socketio`, `data`,
`database`, `flight`, `sensors`, `insights`, `designsystem`, `speech`, `testing`. Current `feature:*` modules:
`dashboard`, `calibration`, `range`, `session`, `training`, `camera`, `settings` (each with a
matching Android-only `feature:<name>:ui`).

## Adding a module
1. Add `include(":core:foo")` to `settings.gradle.kts`.
2. In `core/foo/build.gradle.kts`, add `plugins { alias(libs.plugins.openflight.kmp.library) }`
   for shared (KMP) code, `openflight.kmp.room` for a KMP module holding a Room database (it
   applies `openflight.kmp.library` plus KSP and the Room plugin), or
   `openflight.android.library.compose` for an Android-only Jetpack Compose module (a
   `feature:<name>:ui` or `core:designsystem`).
3. The namespace defaults to `dev.openflight.companion.core.foo`. For KMP: Android + iosArm64 +
   iosSimulatorArm64 targets, Android host tests and the commonTest deps. For Android Compose:
   the Compose BOM, `src/test` (JUnit 4 kotlin.test + assertk) and `src/androidTest` device UI
   tests. Spotless and detekt come with both.
4. Depend on other modules through typesafe accessors: `implementation(projects.core.model)`.

Convention plugin ids: `openflight.kmp.library`, `openflight.kmp.room`,
`openflight.android.library.compose`, `openflight.android.application`, `openflight.spotless`,
`openflight.detekt` (`build-logic/convention`).

## Persistent history (Room)
- `core:database` holds the Room 3 KMP database (`androidx.room3`, bundled SQLite). Schemas are
  exported to `core/database/schemas/` and **committed** (they are source, not goldens). A new
  schema version needs a `Migration` plus a case in `ShotHistoryMigrationTest` (iOS simulator;
  room3-testing's file-based helper is native-only). Never use destructive fallback.
- Only `core:data` sees Room: `ShotHistoryRepository` is the public API.
- Android host tests load the host's SQLite binary from `sqlite-bundled-jvm`
  (`BundledSqliteHostTests.kt`), so in-memory Room tests run in `testAndroidHostTest` too.

## Tests
- commonTest: `kotlin.test` + **assertk** (Truth-style assertions), **Turbine** for Flows,
  `kotlinx-coroutines-test` `runTest`. Android-only tests may use Truth.
- No `test` prefix on test names. Instrumented/UI tests use `given_when_then` or `when_then`,
  on `ComponentActivity` (`createAndroidComposeRule<ComponentActivity>()`).
- Device UI tests: `./gradlew :feature:<name>:ui:connectedDebugAndroidTest` and
  `:androidApp:connectedDebugAndroidTest` (need an emulator; not part of `allTests`).
- `MockServerIT` (`core/data/src/androidHostTest`): the real data layer against a live
  `openflight-server --mock`. `OPENFLIGHT_BACKEND_DIR=<backend checkout> ./gradlew mockServerIT`;
  skipped without the variable, excluded from `testAndroidHostTest`/`allTests`.
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
9. If you touched `iosApp/` Swift code or the API that `shared` exports, the Xcode app builds:
   ```bash
   xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
     -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
   ```
   The `iosApp` target's build phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`
   itself, so there's no separate framework step. The same scheme runs the `iosAppTests` and
   `iosAppUITests` targets. Use `test` with a concrete simulator,
   `-destination 'platform=iOS Simulator,name=<device>'` (list them with `xcrun simctl list
   devices available`).

## CI
`.github/workflows/ci.yml` runs on every push to `main` and on every PR. It mirrors the
invariants above:
- **jvm** (ubuntu): a grep guard against Compose in `feature/*/src/commonMain` and `shared/src`
  and against Compose Multiplatform in the build, then
  `./gradlew spotlessCheck detekt allTests :androidApp:assembleDebug -x iosSimulatorArm64Test`.
- **ios** (macos): `./gradlew iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64`,
  then the `xcodebuild … build` above.

`.github/workflows/mock-server-it.yml` runs `:core:data:mockServerIT` on ubuntu against two
pinned backends (upstream `main` and the `feat/phone-connectivity` fork), only when app code or
the build changes. `.github/dependabot.yml` opens weekly grouped Gradle and Actions updates.

CI doesn't run device UI tests (`connectedDebugAndroidTest`) or the Xcode test targets. Run
those locally when a change affects UI. A green local run of invariants 1–3 and 9 should mean a
green CI run. If they disagree, treat that as a bug to investigate, not noise.

## Gate commits on exit code
Never commit on the strength of a log that "looks clean" — grep/eyeballing build output for
error strings is not a substitute for the process's actual exit status, and a piped command
(`... | grep -v ...`, `... | tail`) silently swallows the left-hand side's exit code unless you
capture it explicitly. Run verification so the exit code survives, e.g.:
```bash
./gradlew spotlessCheck detekt allTests :androidApp:assembleDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64 > log 2>&1; rc=$?
```
then branch on `rc` (or `${PIPESTATUS[0]}` if a pipe is unavoidable), and only commit when
every command in the verification chain returned 0. This project's execution log records at
least one case where a merge was created while a transient failure was masked this way; treat
that as the failure mode to avoid, not a one-off.

`.claude/hooks/commit-gate.sh` (wired in `.claude/settings.json`) enforces this: it runs the
chain before any `git commit` and blocks the commit on a non-zero exit. If it blocks, fix the
cause. Don't bypass it. `/verify` runs the same chain on demand. Path-scoped conventions for
shared KMP code, Compose UI and SwiftUI live in `.claude/rules/`.
